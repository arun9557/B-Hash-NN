package com.bnn.app.transport

import android.content.Context
import android.util.Log
import com.bnn.app.mesh.MeshEngine
import com.bnn.app.mesh.MeshPacket
import com.bnn.app.mesh.RouteTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TransportManager"

/**
 * TransportManager — smart multi-transport router.
 *
 * Manages BLE, WiFi LAN, WiFi Direct, and WiFi Aware transports simultaneously.
 * Picks the best available transport for each outgoing packet.
 * All incoming packets from any transport are forwarded to MeshEngine.
 *
 * Priority (fastest/most-reliable first):
 *   WiFi Aware > WiFi LAN > WiFi Direct > BLE
 */
class TransportManager(
    private val context: Context,
    private val myId: String,
    private val routeTable: RouteTable,
    private val onIncomingPacket: (MeshPacket, String, TransportType) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // All registered transports, in priority order (highest priority first)
    private val transports = mutableListOf<ITransport>()

    // Lazy init — only created if hardware is present
    private var bleTransport: ITransport? = null
    private var wifiLanTransport: ITransport? = null
    private var wifiDirectTransport: ITransport? = null
    private var wifiAwareTransport: ITransport? = null

    // ── Initialization ────────────────────────────────────────────────────────

    fun init(bleTransportImpl: ITransport) {
        bleTransport = bleTransportImpl
        registerTransport(bleTransportImpl)

        // WiFi Aware (highest priority, Android 8+)
        try {
            val aware = WifiAwareTransport(context, myId)
            if (aware.isAvailable) {
                wifiAwareTransport = aware
                registerTransport(aware)
                Log.i(TAG, "WiFi Aware transport registered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WiFi Aware not available: ${e.message}")
        }

        // WiFi LAN (same-network TCP)
        try {
            val lan = WifiLanTransport(context, myId)
            wifiLanTransport = lan
            registerTransport(lan)
            Log.i(TAG, "WiFi LAN transport registered")
        } catch (e: Exception) {
            Log.w(TAG, "WiFi LAN not available: ${e.message}")
        }

        // WiFi Direct (router-free P2P)
        try {
            val direct = WifiDirectTransport(context, myId)
            wifiDirectTransport = direct
            registerTransport(direct)
            Log.i(TAG, "WiFi Direct transport registered")
        } catch (e: Exception) {
            Log.w(TAG, "WiFi Direct not available: ${e.message}")
        }
    }

    private fun registerTransport(transport: ITransport) {
        transport.onPacketReceived = { packet, fromPeerId, type ->
            routeTable.update(fromPeerId, type)
            onIncomingPacket(packet, fromPeerId, type)
        }
        transport.onPeerConnected = { peerId, type ->
            routeTable.update(peerId, type)
            Log.i(TAG, "Peer connected: $peerId via ${type.displayName}")
        }
        transport.onPeerDisconnected = { peerId, type ->
            routeTable.remove(peerId)
            Log.i(TAG, "Peer disconnected: $peerId via ${type.displayName}")
        }
        transports.add(transport)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startAll() {
        scope.launch {
            transports.forEach { transport ->
                try {
                    transport.start()
                    Log.i(TAG, "Started: ${transport.type.displayName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ${transport.type.displayName}: ${e.message}")
                }
            }
        }
    }

    fun stopAll() {
        transports.forEach { transport ->
            try {
                transport.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ${transport.type.displayName}: ${e.message}")
            }
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    /**
     * Send a packet to a specific peer using the best available transport.
     * Falls back down the priority chain if preferred transport fails.
     */
    fun sendTo(peerId: String, packet: MeshPacket): Boolean {
        val preferredType = routeTable.bestTransportFor(peerId)

        // Try preferred transport first
        val preferred = getTransport(preferredType)
        if (preferred != null && preferred.isAvailable && preferred.connectedPeers().contains(peerId)) {
            preferred.sendTo(peerId, packet)
            Log.d(TAG, "Sent to $peerId via ${preferredType.displayName}")
            return true
        }

        // Fallback chain: WiFi Aware → WiFi LAN → WiFi Direct → BLE
        val fallbackOrder = listOf(
            wifiAwareTransport,
            wifiLanTransport,
            wifiDirectTransport,
            bleTransport
        )
        for (transport in fallbackOrder) {
            if (transport == null || !transport.isAvailable) continue
            if (transport.type == preferredType) continue
            if (transport.connectedPeers().contains(peerId) || transport.type == TransportType.BLE) {
                transport.sendTo(peerId, packet)
                routeTable.update(peerId, transport.type)
                Log.d(TAG, "Fallback: sent to $peerId via ${transport.type.displayName}")
                return true
            }
        }
        Log.w(TAG, "Failed to send to $peerId — no transport available")
        return false
    }

    /**
     * Broadcast to ALL directly connected peers across ALL transports.
     * Used for flood/relay routing when destination is unknown.
     */
    fun broadcast(packet: MeshPacket, excludePeerId: String? = null) {
        transports.forEach { transport ->
            if (transport.isAvailable) {
                try {
                    transport.broadcast(packet, excludePeerId)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast failed on ${transport.type.displayName}: ${e.message}")
                }
            }
        }
    }

    // ── Stats / UI ────────────────────────────────────────────────────────────

    /** All connected peers from all transports (deduped by ID) */
    fun allConnectedPeers(): Set<String> {
        return transports.flatMap { it.connectedPeers() }.toSet()
    }

    fun peersByTransport(): Map<TransportType, List<String>> {
        return transports.associate { t ->
            t.type to t.connectedPeers().toList()
        }
    }

    fun availableTransports(): List<TransportType> {
        return transports.filter { it.isAvailable }.map { it.type }
    }

    private fun getTransport(type: TransportType): ITransport? {
        return transports.find { it.type == type }
    }
}
