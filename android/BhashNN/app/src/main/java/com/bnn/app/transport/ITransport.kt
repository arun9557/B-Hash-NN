package com.bnn.app.transport

import com.bnn.app.mesh.MeshPacket

/**
 * ITransport — contract that every physical transport layer must implement.
 *
 * Concrete implementations:
 *  - `BLETransport`        — Bluetooth Low Energy GATT / Advertisement
 *  - `WifiLanTransport`    — TCP/UDP over the local WiFi network
 *  - `WifiDirectTransport` — WiFi P2P (Wi-Fi Direct) group owner / client
 *  - `WifiAwareTransport`  — WiFi Aware (Neighbor Awareness Networking)
 *
 * ## Lifecycle
 * ```
 * transport.onPacketReceived = { packet, fromPeerId, transport -> … }
 * transport.onPeerConnected  = { peerId, transport -> … }
 * transport.onPeerDisconnected = { peerId, transport -> … }
 * transport.start()
 * // … app running …
 * transport.stop()
 * ```
 *
 * ## Threading
 * Callbacks may be invoked on background threads. Callers are responsible for
 * any required main-thread dispatch (e.g. `withContext(Dispatchers.Main)`).
 */
interface ITransport {

    // -------------------------------------------------------------------------
    // Identity & state
    // -------------------------------------------------------------------------

    /** The physical transport type this implementation handles. */
    val type: TransportType

    /**
     * True when the underlying hardware / OS feature is enabled and this
     * transport is ready to send and receive.
     */
    val isAvailable: Boolean

    /**
     * Snapshot of directly-connected peer IDs at the time of access.
     * Implementations must return a defensive copy; callers must not mutate it.
     */
    val connectedPeerIds: Set<String>

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start advertising, scanning, listening, or otherwise activating the
     * underlying transport hardware.  Idempotent — calling [start] on an
     * already-running transport must be a no-op.
     */
    fun start()

    /**
     * Gracefully shut down all active connections and release hardware
     * resources.  Idempotent — calling [stop] on an already-stopped transport
     * must be a no-op.
     */
    fun stop()

    // -------------------------------------------------------------------------
    // Send
    // -------------------------------------------------------------------------

    /**
     * Unicast [packet] to a specific [peerId].
     *
     * @param peerId Target peer's stable node ID.
     * @param packet The [MeshPacket] to deliver.
     * @return `true` if the packet was accepted into the send queue;
     *         `false` if the peer is not connected or the queue is full.
     */
    fun sendTo(peerId: String, packet: MeshPacket): Boolean

    /**
     * Broadcast [packet] to **all** currently connected peers, optionally
     * skipping [excludePeerId] (typically the peer the packet was received
     * from, to avoid echo loops).
     *
     * @param packet        The [MeshPacket] to broadcast.
     * @param excludePeerId Optional peer ID to skip during fanout.
     */
    fun broadcast(packet: MeshPacket, excludePeerId: String? = null)

    // -------------------------------------------------------------------------
    // Inbound callbacks
    // -------------------------------------------------------------------------

    /**
     * Invoked by the transport implementation whenever a complete [MeshPacket]
     * is received from a remote peer.
     *
     * Parameters:
     *  - `packet`    The decoded [MeshPacket].
     *  - `fromPeerId` The stable node ID of the sender.
     *  - `transport` The [TransportType] that delivered this packet
     *                (always equals [type] for a given implementation, but
     *                 passed explicitly for convenience at the mesh router level).
     */
    var onPacketReceived: ((packet: MeshPacket, fromPeerId: String, transport: TransportType) -> Unit)?

    /**
     * Invoked when a new peer successfully connects via this transport.
     *
     * Parameters:
     *  - `peerId`    The stable node ID of the newly connected peer.
     *  - `transport` The [TransportType] over which the connection was made.
     */
    var onPeerConnected: ((peerId: String, transport: TransportType) -> Unit)?

    /**
     * Invoked when a previously connected peer disconnects or is lost.
     *
     * Parameters:
     *  - `peerId`    The stable node ID of the peer that disconnected.
     *  - `transport` The [TransportType] the peer was connected through.
     */
    var onPeerDisconnected: ((peerId: String, transport: TransportType) -> Unit)?
}
