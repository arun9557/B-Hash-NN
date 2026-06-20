package com.bnn.app

import com.bnn.app.mesh.MeshPacket
import com.bnn.app.transport.ITransport
import com.bnn.app.transport.TransportType

/**
 * BLETransportAdapter — wraps existing BLEManager to implement ITransport.
 * This is a bridge/adapter so the existing BLE code plugs into TransportManager.
 */
class BLETransportAdapter(
    private val bleManager: BLEManager
) : ITransport {

    override val type: TransportType = TransportType.BLE
    override val isAvailable: Boolean get() = true

    override var onPacketReceived: ((MeshPacket, String, TransportType) -> Unit)? = null
    override var onPeerConnected: ((String, TransportType) -> Unit)? = null
    override var onPeerDisconnected: ((String, TransportType) -> Unit)? = null

    override fun start() { bleManager.start() }
    override fun stop()  { bleManager.stop() }

    override fun connectedPeers(): Set<String> =
        bleManager.connectedGatewayId?.let { setOf(it) } ?: emptySet()

    override fun sendTo(peerId: String, packet: MeshPacket) {
        try { bleManager.sendRawPacket(packet.toJson().toString()) }
        catch (e: Exception) { /* ignore */ }
    }

    override fun broadcast(packet: MeshPacket, excludePeerId: String?) {
        try { bleManager.sendRawPacket(packet.toJson().toString()) }
        catch (e: Exception) { /* ignore */ }
    }
}
