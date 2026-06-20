package com.bnn.app.transport

import com.bnn.app.mesh.MeshPacket

/**
 * ITransport — common contract implemented by every radio transport.
 *
 * Threading model:
 *   - [start] and [stop] may be called from any thread.
 *   - Callbacks are invoked on Dispatchers.IO (or BLE handler thread).
 *     Callers must marshal to main thread if updating UI.
 *   - [sendTo] and [broadcast] are safe to call from any coroutine context.
 */
interface ITransport {

    /** Which physical radio this transport uses. */
    val type: TransportType

    /** True when this transport is ready for use. */
    val isAvailable: Boolean

    /** Callback invoked whenever a valid [MeshPacket] is received. */
    var onPacketReceived: ((packet: MeshPacket, fromPeerId: String, transport: TransportType) -> Unit)?

    /** Callback invoked when a new peer connects. */
    var onPeerConnected: ((peerId: String, transport: TransportType) -> Unit)?

    /** Callback invoked when a peer disconnects. */
    var onPeerDisconnected: ((peerId: String, transport: TransportType) -> Unit)?

    /** Start advertising, discovery, and the TCP server (if applicable). */
    fun start()

    /** Stop all radio activity, close sockets, unregister receivers, etc. */
    fun stop()

    /**
     * Send [packet] to a specific peer. No-ops if peer is not connected.
     */
    fun sendTo(peerId: String, packet: MeshPacket)

    /**
     * Send [packet] to all connected peers, optionally excluding one.
     */
    fun broadcast(packet: MeshPacket, excludePeerId: String? = null)

    /** Returns the set of currently connected peer IDs. */
    fun connectedPeers(): Set<String>

    // Convenience property backed by connectedPeers() for TransportManager compat
    val connectedPeerIds: Set<String> get() = connectedPeers()
}
