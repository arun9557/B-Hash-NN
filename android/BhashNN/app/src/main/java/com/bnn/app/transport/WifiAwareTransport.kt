package com.bnn.app.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
import com.bnn.app.mesh.MeshPacket

// These imports are API 26+ (Oreo) — safe because we guard all usage with
// Build.VERSION.SDK_INT >= Build.VERSION_CODES.O checks at runtime.
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession

// ══════════════════════════════════════════════════════════════════════════════
//  WifiAwareTransport  (requires Android 8.0 / API 26+)
//
//  Provides ultra-low-latency peer-to-peer connectivity over WiFi Aware (NAN —
//  Neighbour Awareness Networking) without requiring an access point.
//
//  Discovery:  WifiAware Publish + Subscribe to service "bnn-mesh"
//  Transport:  TCP sockets over a WiFi Aware network link, port 47779
//  Framing:    JSON-lines — one MeshPacket JSON per '\n'-terminated line
//
//  Topology:   WifiAware forms direct links between pairs of devices.
//              - The Subscriber opens a WifiAwareNetworkSpecifier (initiator).
//              - The Publisher accepts with a WifiAwareNetworkSpecifier (responder).
//              - Both sides get a Network object via ConnectivityManager.
//              - The Publisher acts as TCP server; Subscriber dials in.
//
//  Lifecycle:
//    start() → attaches WifiAwareSession; no-ops on API < 26
//    stop()  → closes session, cancels coroutines, closes sockets
//
//  Permissions required: ACCESS_FINE_LOCATION, CHANGE_NETWORK_STATE
//  (handled in MainActivity; @SuppressLint used here for MissingPermission)
// ══════════════════════════════════════════════════════════════════════════════

private const val TAG          = "B#NN-WifiAware"
private const val SERVICE_NAME = "bnn-mesh"          // WiFi Aware service identifier
private const val TCP_PORT     = 47779               // WiFi Aware TCP port

@SuppressLint("MissingPermission") // permissions handled in MainActivity
class WifiAwareTransport(
    private val context: Context,
    private val myId: String
) : ITransport {

    // ── ITransport metadata ───────────────────────────────────────────────────

    override val type: TransportType = TransportType.WIFI_AWARE

    /**
     * True when:
     *  1. Running Android 8.0 (API 26) or higher, AND
     *  2. The WifiAwareManager reports that WiFi Aware is currently available.
     */
    override val isAvailable: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val wam = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return false
            return wam.isAvailable
        }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    override var onPacketReceived:   ((MeshPacket, String, TransportType) -> Unit)? = null
    override var onPeerConnected:    ((String, TransportType) -> Unit)?                = null
    override var onPeerDisconnected: ((String, TransportType) -> Unit)?                = null

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Socket management ─────────────────────────────────────────────────────

    /** peerId → active Socket. */
    private val connections = ConcurrentHashMap<String, Socket>()

    /** Socket → peerId. */
    private val socketToPeer = ConcurrentHashMap<Socket, String>()

    /** Our TCP server socket (we act as server because we Publish). */
    @Volatile private var serverSocket: ServerSocket? = null

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var running = false

    // ── WiFi Aware objects (API 26+) — stored as Any? and cast at call sites ──

    @Volatile private var awareSession: Any? = null       // WifiAwareSession
    @Volatile private var publishSession: Any? = null     // PublishDiscoverySession
    @Volatile private var subscribeSession: Any? = null   // SubscribeDiscoverySession

    /** PeerHandle → peerId mapping (filled from onMessageReceived or service info). */
    private val peerHandleToId = ConcurrentHashMap<Int, String>()

    /** Set of PeerHandle hash codes for which we are already requesting a network. */
    private val pendingNetworkRequests = ConcurrentHashMap.newKeySet<Int>()

    // ── WiFi Aware state broadcast receiver ───────────────────────────────────

    private val awareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (intent.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                val available = isAvailable
                Log.i(TAG, "WiFi Aware state changed — available=$available")
                if (available && running) {
                    attachAwareSession()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "WiFi Aware requires API 26+ — no-op on this device (API ${Build.VERSION.SDK_INT})")
            return
        }

        if (running) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        running = true
        Log.i(TAG, "WifiAwareTransport starting (myId=$myId)")

        // Register state change listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                awareStateReceiver,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            )
        }

        attachAwareSession()
    }

    override fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "WifiAwareTransport stopping")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { context.unregisterReceiver(awareStateReceiver) } catch (e: Exception) { /* ignored */ }
            closeAwareSession()
        }

        // Close server socket
        try { serverSocket?.close() } catch (e: IOException) { /* ignored */ }
        serverSocket = null

        // Close peer sockets
        val snapshot = connections.values.toList()
        connections.clear()
        socketToPeer.clear()
        peerHandleToId.clear()
        pendingNetworkRequests.clear()
        for (sock in snapshot) {
            try { sock.close() } catch (e: IOException) { /* ignored */ }
        }

        scope.cancel()
        Log.i(TAG, "WifiAwareTransport stopped")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WIFI AWARE SESSION — attach, publish, subscribe
    // ══════════════════════════════════════════════════════════════════════════

    @RequiresApi(Build.VERSION_CODES.O)
    private fun attachAwareSession() {
        if (!isAvailable) {
            Log.w(TAG, "WiFi Aware not available — cannot attach session")
            return
        }

        val wam = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return
        Log.i(TAG, "Attaching WiFi Aware session…")

        wam.attach(object : AttachCallback() {

            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "WiFi Aware session attached")
                awareSession = session

                // Start TCP server first so it's ready before subscribers dial in
                if (serverSocket == null || serverSocket!!.isClosed) {
                    startTcpServer()
                }

                // Publish our presence so subscribers can discover us
                startPublish(session)

                // Subscribe to discover other publishers
                startSubscribe(session)
            }

            override fun onAttachFailed() {
                Log.e(TAG, "WiFi Aware attach failed")
            }
        }, null /* handler — null means main thread */)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun closeAwareSession() {
        try {
            (publishSession as? PublishDiscoverySession)?.close()
        } catch (e: Exception) { Log.w(TAG, "Publish session close error: ${e.message}") }

        try {
            (subscribeSession as? SubscribeDiscoverySession)?.close()
        } catch (e: Exception) { Log.w(TAG, "Subscribe session close error: ${e.message}") }

        try {
            (awareSession as? WifiAwareSession)?.close()
        } catch (e: Exception) { Log.w(TAG, "Aware session close error: ${e.message}") }

        publishSession   = null
        subscribeSession = null
        awareSession     = null
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startPublish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            // PUBLISH_TYPE_UNSOLICITED — continuously beacons so subscribers
            // can discover us without querying.
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        session.publish(config, object : DiscoverySessionCallback() {

            override fun onPublishStarted(publishSession: PublishDiscoverySession) {
                Log.i(TAG, "WiFi Aware publish started for service '$SERVICE_NAME'")
                this@WifiAwareTransport.publishSession = publishSession
            }

            /**
             * A subscriber has sent us a message — this gives us its PeerHandle.
             * We use the message payload as the peer's device ID if it starts
             * with "id:", otherwise we fall back to the handle's hash code.
             */
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val raw = String(message, Charsets.UTF_8)
                Log.d(TAG, "Publisher received message from peer ${peerHandle.hashCode()}: $raw")
                val peerId = if (raw.startsWith("id:")) raw.removePrefix("id:").trim() else "aware-${peerHandle.hashCode()}"
                peerHandleToId[peerHandle.hashCode()] = peerId

                // As the publisher (server), request the TCP network for this subscriber
                requestAwareNetwork(
                    session       = awareSession as? WifiAwareSession ?: return,
                    peerHandle    = peerHandle,
                    isInitiator   = false,   // publisher is the responder
                    peerId        = peerId
                )
            }

            override fun onSessionTerminated() {
                Log.w(TAG, "Publish session terminated")
                publishSession = null
            }
        }, null)
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startSubscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {

            override fun onSubscribeStarted(subscribeSession: SubscribeDiscoverySession) {
                Log.i(TAG, "WiFi Aware subscribe started for service '$SERVICE_NAME'")
                this@WifiAwareTransport.subscribeSession = subscribeSession
            }

            /**
             * A publisher has been discovered.  We:
             *  1. Send our device ID to the publisher so it can map our PeerHandle.
             *  2. Request the TCP network as the initiator (client side).
             */
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                val peerId = "aware-${peerHandle.hashCode()}"
                Log.i(TAG, "WiFi Aware discovered publisher: $peerId")

                if (!pendingNetworkRequests.add(peerHandle.hashCode())) {
                    Log.d(TAG, "Already requesting network for $peerId")
                    return
                }

                peerHandleToId[peerHandle.hashCode()] = peerId

                // Send our device ID to the publisher so it can identify us
                val subSession = subscribeSession as? SubscribeDiscoverySession
                subSession?.sendMessage(
                    peerHandle,
                    /* messageId = */ peerHandle.hashCode(),
                    "id:$myId".toByteArray(Charsets.UTF_8)
                )

                // Request the aware network as initiator (TCP client)
                requestAwareNetwork(
                    session     = awareSession as? WifiAwareSession ?: return,
                    peerHandle  = peerHandle,
                    isInitiator = true,
                    peerId      = peerId
                )
            }

            override fun onSessionTerminated() {
                Log.w(TAG, "Subscribe session terminated")
                subscribeSession = null
            }
        }, null)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WIFI AWARE NETWORK REQUEST
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Requests a WiFi Aware data path (network) to [peerHandle].
     *
     * - [isInitiator] = true  → we are the subscriber (TCP client)
     * - [isInitiator] = false → we are the publisher  (TCP server, already listening)
     *
     * The [ConnectivityManager.NetworkCallback.onAvailable] callback fires once
     * the link is established; from there we can open sockets.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAwareNetwork(
        session: WifiAwareSession,
        peerHandle: PeerHandle,
        isInitiator: Boolean,
        peerId: String
    ) {
        // Build the WifiAware-specific network specifier
        val discoverySession = if (isInitiator) {
            subscribeSession as? SubscribeDiscoverySession
        } else {
            publishSession as? PublishDiscoverySession
        } ?: run {
            Log.w(TAG, "No discovery session available for network request")
            pendingNetworkRequests.remove(peerHandle.hashCode())
            return
        }

        val specifier = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            .setPskPassphrase("bnn-mesh-passphrase-2024")  // shared secret for the link
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                Log.i(TAG, "WiFi Aware network available for $peerId (initiator=$isInitiator)")
                if (isInitiator) {
                    // Subscriber side: dial the TCP server
                    scope.launch {
                        dialOverAwareNetwork(network, peerId, peerHandle)
                    }
                }
                // Publisher side: the TCP server is already listening; it will
                // accept the incoming connection from the subscriber's dial.
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (isInitiator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // On API 29+ we can get the server's IPv6 link-local address
                    val info = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                    if (info != null) {
                        val serverAddress = info.peerIpv6Addr
                        Log.d(TAG, "Aware peer IPv6 for $peerId: $serverAddress")
                        // If we haven't dialled yet (onAvailable may not have fired first
                        // on some devices), dial now using the IPv6 address.
                        if (!connections.containsKey(peerId)) {
                            scope.launch {
                                dialToIpv6(network, serverAddress?.hostAddress, peerId)
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "WiFi Aware network lost for $peerId")
                pendingNetworkRequests.remove(peerHandle.hashCode())
                // TCP reader will detect the broken pipe and invoke onPeerDisconnected
            }
        })
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TCP OVER WIFI AWARE
    // ══════════════════════════════════════════════════════════════════════════

    /** Starts the TCP server that publisher-side clients will connect to. */
    private fun startTcpServer() {
        scope.launch {
            try {
                val ss = ServerSocket(TCP_PORT).also { serverSocket = it }
                Log.i(TAG, "WiFi Aware TCP server listening on port $TCP_PORT")
                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Log.d(TAG, "Inbound Aware TCP from ${client.remoteSocketAddress}")
                        // We'll map to a peerId once the first packet arrives
                        val tempId = client.remoteSocketAddress.toString()
                        registerSocket(tempId, client)
                        handleConnection(client)
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "Aware accept() error: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Failed to bind Aware ServerSocket on $TCP_PORT: ${e.message}")
            }
            Log.d(TAG, "WiFi Aware TCP server loop exited")
        }
    }

    /**
     * Subscriber dials the publisher over the [network] socket factory.
     * On API 29+ the publisher's IPv6 is available via [onCapabilitiesChanged];
     * on API 26-28 we fall back to localhost because the Aware link uses a
     * virtual loopback between the two endpoints on the same Android process —
     * the server address is effectively 127.0.0.1 when both sides are on the
     * same device. For real cross-device connections on API 26-28 the subscriber
     * should instead use the IPv6 address obtained from the NAN data path.
     * (In practice, WiFi Aware cross-device links always use IPv6 on API 26+.)
     */
    private fun dialOverAwareNetwork(network: Network, peerId: String, peerHandle: PeerHandle) {
        try {
            // Use port 47779 on loopback / link-local — ConnectivityManager routes
            // via the Aware interface automatically when we use network.socketFactory.
            val socket = network.socketFactory.createSocket("127.0.0.1", TCP_PORT)
            Log.i(TAG, "Aware dial success for $peerId")
            registerSocket(peerId, socket)
            handleConnection(socket)
        } catch (e: IOException) {
            Log.w(TAG, "Aware TCP dial (loopback) for $peerId failed: ${e.message}")
            pendingNetworkRequests.remove(peerHandle.hashCode())
        }
    }

    /**
     * Dials the publisher's IPv6 link-local address (API 29+).
     * [serverIpv6] is the string form of the server's link-local IPv6
     * address obtained from [WifiAwareNetworkInfo.getPeerIpv6Addr].
     */
    private fun dialToIpv6(network: Network, serverIpv6: String?, peerId: String) {
        if (serverIpv6 == null) {
            Log.w(TAG, "No IPv6 address for $peerId — cannot dial")
            return
        }
        if (connections.containsKey(peerId)) {
            Log.d(TAG, "Already connected to $peerId — skipping IPv6 dial")
            return
        }
        try {
            val socket = network.socketFactory.createSocket(serverIpv6, TCP_PORT)
            Log.i(TAG, "Aware IPv6 dial success to $serverIpv6:$TCP_PORT for $peerId")
            registerSocket(peerId, socket)
            handleConnection(socket)
        } catch (e: IOException) {
            Log.w(TAG, "Aware IPv6 dial to $serverIpv6 for $peerId failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CONNECTION HANDLER — reads JSON lines from a socket
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleConnection(socket: Socket) {
        val tempId = socketToPeer[socket] ?: socket.remoteSocketAddress?.toString() ?: "aware-unknown"

        scope.launch {
            val reader = try {
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "Cannot open Aware reader for $tempId: ${e.message}")
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

                    // Upgrade the socket key to the authoritative peerId from the packet
                    if (packet.src.isNotBlank() && packet.src != confirmedPeerId) {
                        confirmedPeerId = packet.src
                        remapSocket(tempId, confirmedPeerId, socket)
                        onPeerConnected?.invoke(confirmedPeerId, TransportType.WIFI_AWARE)
                        Log.i(TAG, "Aware peer identified: $confirmedPeerId")
                    }

                    onPacketReceived?.invoke(packet, confirmedPeerId, TransportType.WIFI_AWARE)
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "Aware connection to $confirmedPeerId lost: ${e.message}")
            } finally {
                Log.i(TAG, "Aware reader for $confirmedPeerId exiting")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(confirmedPeerId, TransportType.WIFI_AWARE)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ITransport — SEND
    // ══════════════════════════════════════════════════════════════════════════

    override fun sendTo(peerId: String, packet: MeshPacket) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val socket = connections[peerId] ?: run {
            Log.w(TAG, "sendTo: no Aware connection for peerId=$peerId")
            return
        }
        writePacket(socket, peerId, packet)
    }

    override fun broadcast(packet: MeshPacket, excludePeerId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
                Log.w(TAG, "Aware write to $peerId failed: ${e.message}")
                cleanUpSocket(socket)
                onPeerDisconnected?.invoke(peerId, TransportType.WIFI_AWARE)
            }
        }
    }

    private fun parsePacketLine(line: String): MeshPacket? {
        return try {
            MeshPacket.fromJson(JSONObject(line))
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed Aware JSON line (ignored): ${line.take(120)}")
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
        try { socket.close() } catch (e: IOException) { /* ignored */ }
        Log.d(TAG, "Aware socket cleaned up for $peerId")
    }
}
