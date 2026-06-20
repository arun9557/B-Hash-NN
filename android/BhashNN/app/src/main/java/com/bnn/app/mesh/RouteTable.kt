package com.bnn.app.mesh

import com.bnn.app.transport.TransportType
import java.util.concurrent.ConcurrentHashMap

/**
 * RouteTable — tracks which transport to use to reach each known peer.
 *
 * Entries are upserted every time a packet arrives from a peer (the transport
 * layer knows which physical channel the frame came in on).  A last-seen
 * timestamp is recorded so stale entries can be pruned during idle periods.
 *
 * All public methods are thread-safe via [ConcurrentHashMap].
 */
class RouteTable {

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of a single peer's best-known route.
     *
     * @param peerId      Stable node ID of the remote peer.
     * @param transport   Preferred physical transport to reach this peer.
     * @param lastSeenMs  Unix epoch (ms) of the most recent frame from this peer.
     * @param rssi        BLE RSSI in dBm; 0 for WiFi transports (not measured).
     */
    data class PeerRoute(
        val peerId: String,
        val transport: TransportType,
        val lastSeenMs: Long = System.currentTimeMillis(),
        val rssi: Int = 0
    )

    /** Internal store: peerId → best route. */
    private val routes = ConcurrentHashMap<String, PeerRoute>()

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Upsert the route for [peerId].
     * Call this every time a packet is received so [lastSeenMs] stays fresh.
     *
     * @param peerId    The originating peer's stable node ID.
     * @param transport The transport on which the frame arrived.
     * @param rssi      BLE RSSI (dBm); pass 0 for non-BLE transports.
     */
    fun update(peerId: String, transport: TransportType, rssi: Int = 0) {
        routes[peerId] = PeerRoute(
            peerId      = peerId,
            transport   = transport,
            lastSeenMs  = System.currentTimeMillis(),
            rssi        = rssi
        )
    }

    /**
     * Remove the route for [peerId] (e.g. on explicit disconnect).
     */
    fun remove(peerId: String) {
        routes.remove(peerId)
    }

    /**
     * Prune all routes whose [PeerRoute.lastSeenMs] is older than [timeoutMs].
     * Returns the list of peer IDs that were removed.
     */
    fun pruneStale(timeoutMs: Long = 120_000L): List<String> {
        val now = System.currentTimeMillis()
        val stale = routes.entries
            .filter { (_, route) -> (now - route.lastSeenMs) > timeoutMs }
            .map { it.key }
        stale.forEach { routes.remove(it) }
        return stale
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns the preferred [TransportType] to reach [peerId].
     * Falls back to [TransportType.BLE] if the peer is unknown (safe default).
     */
    fun bestTransportFor(peerId: String): TransportType =
        routes[peerId]?.transport ?: TransportType.BLE

    /**
     * Returns a snapshot of all currently known peer routes.
     */
    fun allPeers(): List<PeerRoute> = routes.values.toList()

    /**
     * Returns the IDs of all peers reachable via a specific [TransportType].
     */
    fun peersByTransport(type: TransportType): List<String> =
        routes.values
            .filter { it.transport == type }
            .map { it.peerId }

    /**
     * Returns the [PeerRoute] for [peerId], or null if not known.
     */
    fun routeFor(peerId: String): PeerRoute? = routes[peerId]

    /**
     * Returns true if [peerId] has not sent a frame in the last [timeoutMs] ms,
     * or is entirely unknown to this table.
     */
    fun isStale(peerId: String, timeoutMs: Long = 120_000L): Boolean {
        val route = routes[peerId] ?: return true
        return (System.currentTimeMillis() - route.lastSeenMs) > timeoutMs
    }

    /** Returns the total number of tracked peers. */
    fun size(): Int = routes.size

    /** Wipes all entries (use on full network reset or test teardown). */
    fun clear() = routes.clear()
}
