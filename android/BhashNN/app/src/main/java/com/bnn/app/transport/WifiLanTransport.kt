package com.bnn.app.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// ══════════════════════════════════════════════════════════════════════════════
//  WifiLanTransport
//
//  Provides full-mesh peer-to-peer connectivity over a shared WiFi LAN.
//
//  Discovery:  Android NSD (mDNS/DNS-SD) — service type "_bnn._tcp"
//  Transport:  Plain TCP sockets, port 47777
//  Framing:    JSON-lines — one MeshPacket JSON per '\n'-terminated line
//
//  Lifecycle:
//    start() → registers NSD service + starts ServerSocket coroutine
//    stop()  → unregisters NSD, closes all sockets, cancels scope
//
//  Thread safety: all socket maps use ConcurrentHashMap; NSD callbacks are
//  dispatched on an arbitrary thread and hand off to the IO coroutine scope.
// ══════════════════════════════════════════════════════════════════════════════

private const val TAG            = "B#NN-WifiLAN"
private const val SERVICE_TYPE   = "_bnn._tcp"       // mDNS service type
private const val SERVICE_NAME   = "bnn-mesh"        // instance name prefix
private const val TCP_PORT       = 47777             // well-known LAN TCP port
private const val CONNECT_TIMEOUT_MS = 5_000         // socket connect timeout

@SuppressLint("MissingPermission") // WiFi / Network permissions handled in MainActivity
class WifiLanTransport(
    private val context: Context,
    private val myId: String
) : ITransport {

    // ── ITransport metadata ───────────────────────────────────────────────────

    override val type: TransportType = TransportType.WIFI_LAN

    /**
     * Reports true when WiFi is connected (i.e. we have a LAN to work on).
     * We check WifiManager rather than ConnectivityManager so we also pass on
     * Android 12+ without CHANGE_NETWORK_STATE.
     */
    override val isAvailable: Boolean
        get() {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            // If the network ID is -1 the device is not associated with any AP
            return wm.isWifiEnabled && (info?.networkId ?: -1) != -1
        }

    // ── Callbacks (set by MeshRouter after construction) ──────────────────────

    override var onPacketReceived:   ((MeshPacket, String, TransportType) -> Unit)? = null
    override var onPeerConnected:    ((String, TransportType) -> Unit)?                = null
    override var onPeerDisconnected: ((String, TransportType) -> Unit)?                = null

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * peerId → live Socket.  Key is the remote BnnDeviceIdentifier received in
     * the first MeshPacket from that peer, or the raw "host:port" if no packet
     * has arrived yet.
     */
    private val connections = ConcurrentHashMap<String, Socket>()

    /**
     * Reverse index: Socket → peerId.  Kept in sync with [connections] so we
     * can look up the peerId when a reader detects a broken pipe.
     */
    private val socketToPeer = ConcurrentHashMap<Socket, String>()

    /** Our registered NSD service name (may have a numeric suffix added by NSD). */
    @Volatile private var registeredServiceName: String? = null

    /** NSD system service. */
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /** Coroutine scope for all socket I/O. SupervisorJob ensures one failing
     *  child does not cancel the entire transport. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The server socket that accepts inbound TCP connections. */
    @Volatile private var serverSocket: ServerSocket? = null

    /** Whether [start] has been called and [stop] has not yet been called. */
    @Volatile private var running = false

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun start() {
        if (running) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        running = true
        Log.i(TAG, "WifiLanTransport starting (myId=$myId)")

        startTcpServer()
        registerNsdService()
        startNsdDiscovery()
    }

    override fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "WifiLanTransport stopping")

        // 1. Unregister NSD (best-effort, errors logged only)
        safeUnregisterNsd()
        safeStopNsdDiscovery()

        // 2. Close server socket — this causes the accept() loop to throw and exit
        try { serverSocket?.close() } catch (e: IOException) { /* ignored */ }
        serverSocket = null

        // 3. Close all peer sockets
        val snapshot = connections.values.toList()
        connections.clear()
        socketToPeer.clear()
        for (sock in snapshot) {
            try { sock.close() } catch (e: IOException) { /* ignored */ }
        }

        // 4. Cancel all coroutines
        scope.cancel()
        Log.i(TAG, "WifiLanTransport stopped — ${snapshot.size} sockets closed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TCP SERVER — accepts inbound connections from peers
    // ══════════════════════════════════════════════════════════════════════════

    private fun startTcpServer() {
        scope.launch {
            try {
                val ss = ServerSocket(TCP_PORT).also { serverSocket = it }
                Log.i(TAG, "TCP server listening on port $TCP_PORT")
                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Log.d(TAG, "Inbound TCP connection from ${client.remoteSocketAddress}")
                        // Hand off to a dedicated reader coroutine
                        handleConnection(client, initiatedByUs = false)
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "accept() error: ${e.message}")
                        // If still running, the socket may have been recreated; break to allow
                        // the outer while condition to re-check.
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Failed to bind ServerSocket on $TCP_PORT: ${e.message}")
            }
            Log.d(TAG, "TCP server loop exited")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NSD — service advertisement
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Constructs the NsdServiceInfo and calls [NsdManager.registerService].
     * The service name is "$SERVICE_NAME-$myId" so peers can extract the
     * device identifier directly from the mDNS advertisement without connecting.
     */
    private fun registerNsdService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME-$myId"
            serviceType = SERVICE_TYPE
            port        = TCP_PORT
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD registration failed: errorCode=$errorCode")
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD unregistration failed: errorCode=$errorCode")
        }

        override fun onServiceRegistered(info: NsdServiceInfo) {
            // Android may append a numeric suffix if the name conflicts.
            registeredServiceName = info.serviceName
            Log.i(TAG, "NSD service registered: ${info.serviceName} on port ${info.port}")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            registeredServiceName = null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NSD — service discovery
    // ══════════════════════════════════════════════════════════════════════════

    /** Whether discovery is currently active (guarded by running flag). */
    @Volatile private var discoveryActive = false

    private fun startNsdDiscovery() {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        discoveryActive = true
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "NSD discovery started for $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "NSD discovery stopped for $serviceType")
            discoveryActive = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery start failed: errorCode=$errorCode")
            discoveryActive = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "NSD discovery stop failed: errorCode=$errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            Log.d(TAG, "NSD found service: $name")

            // Skip our own advertisement
            if (name == registeredServiceName) return
            // Only connect to B#NN mesh services
            if (!name.startsWith(SERVICE_NAME, ignoreCase = true)) return

            // Resolve to obtain host + port, then connect
            nsdManager.resolveService(serviceInfo, buildResolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            Log.d(TAG, "NSD lost service: $name")
            // The reader loop will detect the broken pipe and clean up.
        }
    }

    /**
     * Builds a fresh [NsdManager.ResolveListener] per resolution call.
     * NsdManager requires a distinct instance per resolve request.
     */
    private fun buildResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: run {
                    Log.w(TAG, "Resolved service has no host: ${serviceInfo.serviceName}")
                    return
                }
                val port = serviceInfo.port
                Log.i(TAG, "NSD resolved ${serviceInfo.serviceName} → $host:$port")

                // Try to extract a peerId from the service name:
                // format is "$SERVICE_NAME-$peerId"
                val peerIdFromName = serviceInfo.serviceName
                    .removePrefix("$SERVICE_NAME-")
                    .takeIf { it.isNotBlank() }

                // Symmetrical deduplication: only dial out if our ID sorts lower.
                // This ensures exactly one TCP connection between any two peers.
                if (peerIdFromName != null && myId >= peerIdFromName) {
                    Log.d(TAG, "Skipping outbound dial to $peerIdFromName (symmetry rule)")
                    return
                }

                // Don't dial if we're already connected to this peer
                if (peerIdFromName != null && connections.containsKey(peerIdFromName)) {
                    Log.d(TAG, "Already connected to $peerIdFromName — skipping")
                    return
                }

                // Launch outbound connection
                scope.launch {
                    connectToPeer(host, port, peerIdFromName)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  OUTBOUND CONNECTIONS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dials TCP to [host]:[port], registers the socket, and spawns a reader.
     * [tentativePeerId] is used as the key until we receive the first packet
     * (which contains the authoritative src field).
     */
    private fun connectToPeer(host: String, port: Int, tentativePeerId: String?) {
        try {
            Log.i(TAG, "Dialing $host:$port (tentativePeerId=$tentativePeerId)")
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            val key = tentativePeerId ?: "$host:$port"
            registerSocket(key, socket)
            handleConnection(socket, initiatedByUs = true)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to connect to $host:$port — ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONNECTION HANDLER — reads JSON lines from a socket
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Registers [socket] under [peerId] and launches a coroutine that reads
     * newline-delimited JSON packets until the connection closes or an error
     * occurs.  The first packet received from any peer is used to confirm /
     * update the peerId.
     */
    private fun handleConnection(socket: Socket, initiatedByUs: Boolean) {
        // For inbound connections we don't know the peer ID yet; use a placeholder.
        val tempId = if (initiatedByUs) {
            socketToPeer[socket] ?: socket.remoteSocketAddress.toString()
        } else {
            socket.remoteSocketAddress.toString()
        }

        if (!initiatedByUs) {
            // Register the socket under a temporary key until the first packet arrives.
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

            // Track the "confirmed" peer ID (updated on first packet with src field)
            var confirmedPeerId = tempId

            try {
                var line: String?
                while (isActive && !socket.isClosed) {
                    line = reader.readLine() ?: break   // null = remote closed connection
                    if (line.isBlank()) continue

                    val packet = parsePacketLine(line) ?: continue

                    // If the src field gives us the peer's real ID, remap the socket key.
                    if (packet.src.isNotBlank() && packet.src != confirmedPeerId) {
                        confirmedPeerId = packet.src
                        remapSocket(tempId, confirmedPeerId, socket)
                        onPeerConnected?.invoke(confirmedPeerId, TransportType.WIFI_LAN)
                        Log.i(TAG, "Peer identified: $confirmedPeerId (was $tempId)")
                    }

                    onPacketReceived?.invoke(packet, confirmedPeerId, TransportType.WIFI_LAN)
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "Connection to $confirmedPeerId lost: ${e.message}")
            } finally {
                Log.i(TAG, "Reader for $confirmedPeerId exiting")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(confirmedPeerId, TransportType.WIFI_LAN)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ITransport — SEND
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Sends [packet] to the peer identified by [peerId].
     * Writes the JSON string followed by '\n' using a PrintWriter (thread-safe
     * per socket since each socket is owned by one writer coroutine context,
     * but we synchronise on the socket to guard against concurrent broadcast
     * and sendTo calls).
     */
    override fun sendTo(peerId: String, packet: MeshPacket) {
        val socket = connections[peerId] ?: run {
            Log.w(TAG, "sendTo: no connection for peerId=$peerId")
            return
        }
        writePacket(socket, peerId, packet)
    }

    /**
     * Broadcasts [packet] to all currently connected peers, skipping
     * [excludePeerId] if provided.  Used by MeshRouter for flooding.
     */
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

    /**
     * Serialises [packet] to a JSON line and writes it to [socket]'s output
     * stream.  If the write fails the socket is closed and the peer removed.
     */
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
                Log.w(TAG, "Write to $peerId failed: ${e.message}")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(peerId, TransportType.WIFI_LAN)
            }
        }
    }

    /** Attempts to parse a raw JSON line into a [MeshPacket]; returns null on error. */
    private fun parsePacketLine(line: String): MeshPacket? {
        return try {
            MeshPacket.fromJson(JSONObject(line))
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed JSON line (ignored): ${line.take(120)}")
            null
        }
    }

    /** Adds [socket] to both maps under [peerId]. */
    private fun registerSocket(peerId: String, socket: Socket) {
        connections[peerId]    = socket
        socketToPeer[socket]   = peerId
    }

    /**
     * Moves a socket registration from [oldPeerId] to [newPeerId].
     * Used when the authoritative peer ID arrives in the first packet.
     */
    private fun remapSocket(oldPeerId: String, newPeerId: String, socket: Socket) {
        connections.remove(oldPeerId)
        connections[newPeerId]  = socket
        socketToPeer[socket]    = newPeerId
    }

    /** Closes [socket] and removes it from both maps. */
    private fun cleanUpSocket(socket: Socket) {
        val peerId = socketToPeer.remove(socket) ?: socket.remoteSocketAddress.toString()
        connections.remove(peerId)
        try { socket.close() } catch (e: IOException) { /* ignored */ }
        Log.d(TAG, "Socket cleaned up for $peerId")
    }

    private fun safeUnregisterNsd() {
        if (registeredServiceName == null) return
        try {
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregister error: ${e.message}")
        }
    }

    private fun safeStopNsdDiscovery() {
        if (!discoveryActive) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD stop-discovery error: ${e.message}")
        }
    }
}
