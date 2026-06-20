package com.bnn.app.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.bnn.app.mesh.MeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// ══════════════════════════════════════════════════════════════════════════════
//  WifiDirectTransport
//
//  Provides peer-to-peer connectivity over WiFi Direct (P2P) using the
//  Android WifiP2pManager API.
//
//  Discovery:  WifiP2pManager peer discovery + BroadcastReceiver
//  Transport:  Plain TCP sockets, port 47778
//  Framing:    JSON-lines — one MeshPacket JSON per '\n'-terminated line
//
//  Topology:   WiFi Direct always forms a star topology — one device becomes
//              the Group Owner (GO) and all others connect as clients.
//              - The GO runs a TCP ServerSocket on port 47778.
//              - Clients connect to the GO's IP address (from WifiP2pInfo).
//
//  Permissions: ACCESS_FINE_LOCATION and (API 33+) NEARBY_WIFI_DEVICES are
//               required. They are requested in MainActivity; this class is
//               annotated @SuppressLint("MissingPermission") accordingly.
//
//  Lifecycle:
//    start() → registers BroadcastReceiver, starts peer discovery
//    stop()  → stops discovery, unregisters receiver, closes sockets
// ══════════════════════════════════════════════════════════════════════════════

private const val TAG          = "B#NN-WifiDirect"
private const val TCP_PORT     = 47778               // WiFi Direct TCP port
private const val CONNECT_TIMEOUT_MS = 8_000         // socket connect timeout

@SuppressLint("MissingPermission") // permissions handled in MainActivity
class WifiDirectTransport(
    private val context: Context,
    private val myId: String
) : ITransport {

    // ── ITransport metadata ───────────────────────────────────────────────────

    override val type: TransportType = TransportType.WIFI_DIRECT

    /**
     * True when the device has the WifiP2pManager system service available.
     * Hardware presence alone does not guarantee that P2P is usable (it may
     * be disabled by the user), but that state is surfaced via
     * WIFI_P2P_STATE_CHANGED_ACTION at runtime.
     */
    override val isAvailable: Boolean
        get() = context.getSystemService(Context.WIFI_P2P_SERVICE) != null

    // ── Callbacks ─────────────────────────────────────────────────────────────

    override var onPacketReceived:   ((MeshPacket, String, TransportType) -> Unit)? = null
    override var onPeerConnected:    ((String, TransportType) -> Unit)?                = null
    override var onPeerDisconnected: ((String, TransportType) -> Unit)?                = null

    // ── WifiP2p system objects ────────────────────────────────────────────────

    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }

    /** The P2P channel — must be closed on stop(). */
    private var channel: WifiP2pManager.Channel? = null

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Socket management ─────────────────────────────────────────────────────

    /** peerId → active Socket. */
    private val connections = ConcurrentHashMap<String, Socket>()

    /** Socket → peerId (reverse index for cleanup). */
    private val socketToPeer = ConcurrentHashMap<Socket, String>()

    /** The TCP server socket (only used when this device is Group Owner). */
    @Volatile private var serverSocket: ServerSocket? = null

    // ── State flags ───────────────────────────────────────────────────────────

    @Volatile private var running          = false
    @Volatile private var wifiP2pEnabled   = false
    @Volatile private var isGroupOwner     = false

    /** Set of device addresses we have already attempted to connect to, to
     *  avoid hammering the same peer repeatedly on each peers-changed event. */
    private val pendingOrConnected = ConcurrentHashMap.newKeySet<String>()

    // ══════════════════════════════════════════════════════════════════════════
    //  BROADCAST RECEIVER
    // ══════════════════════════════════════════════════════════════════════════

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onWifiP2pStateChanged(intent)
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged(intent)
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> { /* device info update — no action needed */ }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun start() {
        if (running) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        running = true
        Log.i(TAG, "WifiDirectTransport starting (myId=$myId)")

        // 1. Open the WifiP2p channel
        channel = manager.initialize(context, context.mainLooper) {
            Log.w(TAG, "WifiP2p channel disconnected — will not auto-reconnect")
        }

        // 2. Register the broadcast receiver
        context.registerReceiver(receiver, intentFilter)

        // 3. Start peer discovery immediately
        discoverPeers()
    }

    override fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "WifiDirectTransport stopping")

        // 1. Stop P2P discovery
        channel?.let { ch ->
            try {
                manager.stopPeerDiscovery(ch, actionListener("stopPeerDiscovery"))
                manager.removeGroup(ch, actionListener("removeGroup"))
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping P2P: ${e.message}")
            }
        }

        // 2. Unregister receiver
        try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* ignored */ }

        // 3. Close channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try { channel?.close() } catch (e: Exception) { /* ignored */ }
        }
        channel = null

        // 4. Close server socket
        try { serverSocket?.close() } catch (e: IOException) { /* ignored */ }
        serverSocket = null

        // 5. Close peer sockets
        val snapshot = connections.values.toList()
        connections.clear()
        socketToPeer.clear()
        pendingOrConnected.clear()
        for (sock in snapshot) {
            try { sock.close() } catch (e: IOException) { /* ignored */ }
        }

        // 6. Cancel coroutines
        scope.cancel()
        Log.i(TAG, "WifiDirectTransport stopped")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BROADCAST RECEIVER HANDLERS
    // ══════════════════════════════════════════════════════════════════════════

    /** WiFi P2P enabled/disabled state changed. */
    private fun onWifiP2pStateChanged(intent: Intent) {
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        wifiP2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        Log.i(TAG, "WiFi P2P state: ${if (wifiP2pEnabled) "ENABLED" else "DISABLED"}")
        if (wifiP2pEnabled && running) {
            discoverPeers()
        }
    }

    /**
     * The peer list has changed — request the updated list and attempt to
     * connect to any new B#NN devices.
     */
    private fun onPeersChanged() {
        val ch = channel ?: return
        manager.requestPeers(ch) { peerList ->
            Log.d(TAG, "P2P peer list: ${peerList.deviceList.size} devices")
            for (device in peerList.deviceList) {
                attemptConnect(device)
            }
        }
    }

    /**
     * The P2P connection state changed — check if we've joined a group and
     * set up TCP accordingly.
     */
    private fun onConnectionChanged(intent: Intent) {
        val ch = channel ?: return

        @Suppress("DEPRECATION")
        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        if (networkInfo?.isConnected == true) {
            // We have joined a P2P group — request connection info (GO IP, isGroupOwner, etc.)
            manager.requestConnectionInfo(ch) { info ->
                handleGroupInfo(info)
            }
        } else {
            // Disconnected from the group
            Log.i(TAG, "P2P group disconnected — closing TCP connections")
            isGroupOwner = false
            val snapshot = connections.values.toList()
            connections.clear()
            socketToPeer.clear()
            pendingOrConnected.clear()
            try { serverSocket?.close() } catch (e: IOException) { /* ignored */ }
            serverSocket = null
            for (sock in snapshot) {
                val peer = socketToPeer[sock] ?: sock.remoteSocketAddress.toString()
                try { sock.close() } catch (e: IOException) { /* ignored */ }
                onPeerDisconnected?.invoke(peer, TransportType.WIFI_DIRECT)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  P2P GROUP HANDLING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called once the WifiP2pInfo is available after connecting to a group.
     * - Group Owner (GO): starts the TCP server on port 47778
     * - Client: dials the GO's IP on port 47778
     */
    private fun handleGroupInfo(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        Log.i(TAG, "P2P group formed — isGroupOwner=$isGroupOwner, GO=${info.groupOwnerAddress?.hostAddress}")

        if (info.isGroupOwner) {
            // Start TCP server (idempotent — no-op if already running)
            if (serverSocket == null || serverSocket!!.isClosed) {
                startTcpServer()
            }
        } else {
            // Connect to the Group Owner
            val goAddress = info.groupOwnerAddress ?: run {
                Log.w(TAG, "No GO address in WifiP2pInfo")
                return
            }
            val goAddressStr = goAddress.hostAddress ?: return
            if (!pendingOrConnected.add("GO:$goAddressStr")) {
                // Already connecting / connected
                return
            }
            scope.launch {
                connectToPeer(goAddress, TCP_PORT)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PEER DISCOVERY & CONNECT
    // ══════════════════════════════════════════════════════════════════════════

    /** Triggers WifiP2p peer discovery. */
    private fun discoverPeers() {
        val ch = channel ?: return
        manager.discoverPeers(ch, actionListener("discoverPeers"))
    }

    /**
     * Evaluates a discovered [WifiP2pDevice] and connects if:
     *  1. Its name contains "BNN" or starts with "Phone_" (B#NN mesh device)
     *  2. We haven't already connected or started connecting to this address
     *  3. This device is not the Group Owner (it would connect to us instead)
     */
    private fun attemptConnect(device: WifiP2pDevice) {
        val name = device.deviceName ?: ""
        val address = device.deviceAddress ?: return

        val isBnnDevice = name.contains("BNN", ignoreCase = true)
                || name.contains("B#NN", ignoreCase = true)
                || name.startsWith("Phone_", ignoreCase = true)

        if (!isBnnDevice) {
            Log.d(TAG, "Ignoring non-BNN device: $name")
            return
        }

        // Avoid duplicate connection attempts
        if (!pendingOrConnected.add(address)) {
            Log.d(TAG, "Already connecting/connected to $name ($address)")
            return
        }

        Log.i(TAG, "Initiating P2P connect to $name ($address)")
        val config = WifiP2pConfig().apply {
            deviceAddress = address
        }

        val ch = channel ?: return
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "P2P connect request sent to $name ($address)")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "P2P connect failed to $name ($address): reason=$reason")
                pendingOrConnected.remove(address)
            }
        })
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TCP SERVER (Group Owner)
    // ══════════════════════════════════════════════════════════════════════════

    private fun startTcpServer() {
        scope.launch {
            try {
                val ss = ServerSocket(TCP_PORT).also { serverSocket = it }
                Log.i(TAG, "WiFi Direct TCP server listening on port $TCP_PORT")
                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Log.d(TAG, "Inbound P2P connection from ${client.remoteSocketAddress}")
                        handleConnection(client)
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "P2P accept() error: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Failed to bind P2P ServerSocket on $TCP_PORT: ${e.message}")
            }
            Log.d(TAG, "WiFi Direct TCP server loop exited")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TCP CLIENT (Group Client)
    // ══════════════════════════════════════════════════════════════════════════

    private fun connectToPeer(address: InetAddress, port: Int) {
        try {
            Log.i(TAG, "Dialing P2P TCP $address:$port")
            val socket = Socket()
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)

            val tempKey = address.hostAddress ?: address.toString()
            registerSocket(tempKey, socket)
            handleConnection(socket)
        } catch (e: IOException) {
            Log.w(TAG, "P2P TCP connect to $address:$port failed: ${e.message}")
            pendingOrConnected.remove("GO:${address.hostAddress}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONNECTION HANDLER — reads JSON lines
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleConnection(socket: Socket) {
        val tempId = socketToPeer[socket] ?: socket.remoteSocketAddress.toString()

        // Ensure socket is registered
        if (!socketToPeer.containsKey(socket)) {
            registerSocket(tempId, socket)
        }

        scope.launch {
            val reader = try {
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "Cannot open reader for $tempId: ${e.message}")
                cleanUpSocket(socket)
                return@launch
            }

            var confirmedPeerId = tempId

            try {
                var line: String?
                while (isActive && !socket.isClosed) {
                    line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    val packet = parsePacketLine(line) ?: continue

                    // Remap socket key to the authoritative src peer ID
                    if (packet.src.isNotBlank() && packet.src != confirmedPeerId) {
                        confirmedPeerId = packet.src
                        remapSocket(tempId, confirmedPeerId, socket)
                        onPeerConnected?.invoke(confirmedPeerId, TransportType.WIFI_DIRECT)
                        Log.i(TAG, "P2P peer identified: $confirmedPeerId")
                    }

                    onPacketReceived?.invoke(packet, confirmedPeerId, TransportType.WIFI_DIRECT)
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "P2P connection to $confirmedPeerId lost: ${e.message}")
            } finally {
                Log.i(TAG, "P2P reader for $confirmedPeerId exiting")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(confirmedPeerId, TransportType.WIFI_DIRECT)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ITransport — SEND
    // ══════════════════════════════════════════════════════════════════════════

    override fun sendTo(peerId: String, packet: MeshPacket) {
        val socket = connections[peerId] ?: run {
            Log.w(TAG, "sendTo: no P2P connection for peerId=$peerId")
            return
        }
        writePacket(socket, peerId, packet)
    }

    override fun broadcast(packet: MeshPacket, excludePeerId: String?) {
        for ((peerId, socket) in connections) {
            if (peerId == excludePeerId) continue
            writePacket(socket, peerId, packet)
        }
    }

    override fun connectedPeers(): Set<String> = connections.keys.toSet()

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun writePacket(socket: Socket, peerId: String, packet: MeshPacket) {
        scope.launch {
            try {
                synchronized(socket) {
                    if (socket.isClosed) return@launch
                    val writer = PrintWriter(
                        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8),
                        /* autoFlush = */ true
                    )
                    writer.println(packet.toJson().toString())
                }
            } catch (e: IOException) {
                Log.w(TAG, "P2P write to $peerId failed: ${e.message}")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(peerId, TransportType.WIFI_DIRECT)
            }
        }
    }

    private fun parsePacketLine(line: String): MeshPacket? {
        return try {
            MeshPacket.fromJson(JSONObject(line))
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed P2P JSON line (ignored): ${line.take(120)}")
            null
        }
    }

    private fun registerSocket(peerId: String, socket: Socket) {
        connections[peerId]  = socket
        socketToPeer[socket] = peerId
    }

    private fun remapSocket(oldPeerId: String, newPeerId: String, socket: Socket) {
        connections.remove(oldPeerId)
        connections[newPeerId]  = socket
        socketToPeer[socket]    = newPeerId
    }

    private fun cleanUpSocket(socket: Socket) {
        val peerId = socketToPeer.remove(socket) ?: socket.remoteSocketAddress?.toString() ?: "?"
        connections.remove(peerId)
        pendingOrConnected.remove(peerId)
        try { socket.close() } catch (e: IOException) { /* ignored */ }
        Log.d(TAG, "P2P socket cleaned up for $peerId")
    }

    /**
     * Returns a simple [WifiP2pManager.ActionListener] that only logs results.
     * Keeps the call-site code concise.
     */
    private fun actionListener(label: String): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "$label: success")
            }
            override fun onFailure(reason: Int) {
                // reason codes: ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                val desc = when (reason) {
                    WifiP2pManager.ERROR         -> "ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY          -> "BUSY"
                    else                         -> "UNKNOWN($reason)"
                }
                Log.w(TAG, "$label: failed — $desc")
            }
        }
}
