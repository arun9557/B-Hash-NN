package com.bnn.app.mesh

import android.util.Log
import com.bnn.app.BLECallback
import com.bnn.app.transport.TransportManager
import com.bnn.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "MeshEngine"
private const val MAX_TTL = 10
private const val MY_PEER_PREFIX = "Phone_"

/**
 * MeshEngine — the central hub of the B#NN hybrid mesh network.
 *
 * Responsibilities:
 *  1. Receive packets from ALL transports (BLE, WiFi LAN, WiFi Direct, WiFi Aware)
 *  2. Deduplicate packets (drop duplicates)
 *  3. Route packets:
 *     - If destined for this device → deliver to UI (via BLECallback)
 *     - If destined for known peer → forward via best transport
 *     - If destination unknown → flood to all (TTL-limited)
 *  4. Expose mesh statistics to the UI
 *
 * Architecture: MeshEngine is the ONLY thing that calls TransportManager.
 * All transports are dumb pipes — they just send/receive bytes.
 */
class MeshEngine(
    private val myId: String,
    private val callback: BLECallback
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dedup = PacketDedup(maxSize = 2000)
    val routeTable = RouteTable()
    private var transportManager: TransportManager? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachTransportManager(tm: TransportManager) {
        transportManager = tm
    }

    fun start() {
        Log.i(TAG, "MeshEngine starting for $myId")
        transportManager?.startAll()
        callback.onStatusChanged("Mesh active · scanning…")
    }

    fun stop() {
        Log.i(TAG, "MeshEngine stopping")
        transportManager?.stopAll()
        dedup.clear()
        callback.onStatusChanged("Stopped")
    }

    // ── Incoming Packet Handler ───────────────────────────────────────────────

    /**
     * Called by TransportManager whenever ANY transport receives a packet.
     * This is the core routing logic.
     */
    fun onPacketReceived(packet: MeshPacket, fromPeerId: String, transport: TransportType) {
        scope.launch {
            // 1. Update route table — we now know how to reach this peer
            routeTable.update(fromPeerId, transport)

            // 2. Deduplication — drop if we've seen this packet before
            if (!dedup.isNew(packet.id)) {
                Log.d(TAG, "Dropped duplicate: ${packet.id}")
                return@launch
            }

            Log.d(TAG, "Packet [${packet.type}] from=$fromPeerId via=${transport.displayName} dst=${packet.dst}")

            // 3. Routing decision
            when {
                packet.dst == myId || packet.dst == "local" -> {
                    // Packet is for ME — deliver to app
                    deliverToApp(packet)
                }

                packet.dst == "server" || packet.dst == "broadcast" || packet.dst == "*" -> {
                    // Server-bound or broadcast: forward to server if we have a gateway,
                    // AND also relay to other peers (mesh flooding)
                    forwardToServer(packet, fromPeerId)
                    relayToMesh(packet, fromPeerId)
                }

                packet.dst.startsWith(MY_PEER_PREFIX) -> {
                    // Peer-to-peer: route to specific peer
                    forwardToPeer(packet, fromPeerId)
                }

                else -> {
                    // Unknown destination — flood
                    relayToMesh(packet, fromPeerId)
                }
            }
        }
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    private fun deliverToApp(packet: MeshPacket) {
        when (packet.type) {
            "response" -> {
                callback.onMessageReceived(packet.payload, isRelay = packet.hops > 0)
            }
            "ping" -> {
                // Reply with pong
                sendPacket(buildPacket("pong", "pong", packet.src))
            }
            "error" -> {
                callback.onError(packet.payload)
            }
            else -> {
                callback.onMessageReceived(packet.payload, isRelay = false)
            }
        }
    }

    private fun forwardToServer(packet: MeshPacket, fromPeerId: String) {
        // In our architecture, "server" is a BLE gateway peer.
        // Find any peer in route table that is the gateway (connected via BLE to laptop)
        val gatewayPeers = routeTable.peersByTransport(TransportType.BLE)
        if (gatewayPeers.isNotEmpty()) {
            val forwarded = packet.copy(hops = packet.hops + 1, ttl = packet.ttl - 1)
            gatewayPeers.forEach { gw ->
                if (gw != fromPeerId) {
                    transportManager?.sendTo(gw, forwarded)
                }
            }
        }
    }

    private fun forwardToPeer(packet: MeshPacket, fromPeerId: String) {
        if (packet.ttl <= 0) {
            Log.w(TAG, "TTL expired for packet ${packet.id}")
            return
        }
        val forwarded = packet.copy(hops = packet.hops + 1, ttl = packet.ttl - 1)

        // Direct route known?
        val targetPeer = packet.dst
        val allPeers = transportManager?.allConnectedPeers() ?: emptySet()
        if (allPeers.contains(targetPeer)) {
            transportManager?.sendTo(targetPeer, forwarded)
        } else {
            // Flood — destination not directly reachable
            relayToMesh(forwarded, fromPeerId)
        }
    }

    private fun relayToMesh(packet: MeshPacket, excludePeerId: String) {
        if (packet.ttl <= 0) return
        val relayed = packet.copy(hops = packet.hops + 1, ttl = packet.ttl - 1)
        transportManager?.broadcast(relayed, excludePeerId = excludePeerId)
    }

    // ── Outgoing ──────────────────────────────────────────────────────────────

    /**
     * Send a user prompt to the AI server via best available path.
     */
    fun sendPrompt(text: String) {
        val packet = buildPacket("request", text, "server")
        dedup.isNew(packet.id) // mark our own packet as seen
        Log.i(TAG, "Sending prompt via mesh: $text")

        val tm = transportManager
        if (tm == null) {
            callback.onError("Mesh not started")
            return
        }

        // Try to find server directly, otherwise flood
        val bleGateway = routeTable.peersByTransport(TransportType.BLE)
        val wifiPeers = routeTable.peersByTransport(TransportType.WIFI_LAN) +
                routeTable.peersByTransport(TransportType.WIFI_DIRECT) +
                routeTable.peersByTransport(TransportType.WIFI_AWARE)

        when {
            bleGateway.isNotEmpty() -> {
                // Direct BLE path to server (original flow)
                tm.sendTo(bleGateway.first(), packet)
            }
            wifiPeers.isNotEmpty() -> {
                // Relay via WiFi mesh peers — one of them might have BLE to server
                wifiPeers.forEach { tm.sendTo(it, packet) }
            }
            else -> {
                // Flood to everyone
                tm.broadcast(packet)
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun buildPacket(type: String, payload: String, dst: String): MeshPacket =
        MeshPacket(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            payload = payload,
            src = myId,
            dst = dst,
            hops = 0,
            ttl = MAX_TTL,
            ts = System.currentTimeMillis()
        )

    private fun sendPacket(packet: MeshPacket) {
        dedup.isNew(packet.id)
        transportManager?.sendTo(packet.dst, packet)
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun allPeerIds(): Set<String> = transportManager?.allConnectedPeers() ?: emptySet()

    fun peersByTransport(): Map<TransportType, List<String>> =
        transportManager?.peersByTransport() ?: emptyMap()

    fun activeTransports(): List<TransportType> =
        transportManager?.availableTransports() ?: emptyList()
}
