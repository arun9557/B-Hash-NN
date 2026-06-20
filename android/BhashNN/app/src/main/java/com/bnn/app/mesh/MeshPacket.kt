package com.bnn.app.mesh

import com.bnn.app.transport.TransportType
import org.json.JSONObject

/**
 * MeshPacket — unified packet for BLE + WiFi mesh routing.
 *
 * Wraps a JSON message with routing metadata:
 *  - [id]        Unique packet UUID (used for deduplication)
 *  - [type]      Message role: "request", "response", "ping", "pong", "relay"
 *  - [payload]   Opaque string body (JSON, Base64, plain text, etc.)
 *  - [src]       Source node ID (originator)
 *  - [dst]       Destination node ID, or "*" for broadcast
 *  - [hops]      Number of relay hops already taken
 *  - [ttl]       Max hops remaining before the packet is discarded
 *  - [ts]        Unix epoch timestamp (ms) when packet was created
 *  - [transport] Which physical transport this packet arrived on
 */
data class MeshPacket(
    val id: String,
    val type: String,           // "request", "response", "ping", "pong", "relay"
    val payload: String,
    val src: String,
    val dst: String,
    var hops: Int,
    var ttl: Int,
    val ts: Long,
    val transport: TransportType = TransportType.UNKNOWN
) {

    companion object {

        /**
         * Deserialise a [MeshPacket] from a [JSONObject].
         * Returns null only if a hard JSON exception is thrown; missing fields get safe defaults.
         *
         * @param json      The raw JSON object received from the wire.
         * @param transport The physical transport the object arrived on (injected by the transport layer).
         */
        fun fromJson(
            json: JSONObject,
            transport: TransportType = TransportType.UNKNOWN
        ): MeshPacket? {
            return try {
                MeshPacket(
                    id        = json.optString("id", java.util.UUID.randomUUID().toString()),
                    type      = json.optString("type", "unknown"),
                    payload   = json.optString("payload", ""),
                    src       = json.optString("src", ""),
                    dst       = json.optString("dst", ""),
                    hops      = json.optInt("hops", 0),
                    ttl       = json.optInt("ttl", 5),
                    ts        = json.optLong("ts", System.currentTimeMillis()),
                    transport = transport
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Serialise this packet to a [JSONObject] suitable for transmission.
     * Note: [transport] is intentionally excluded — it is a local routing annotation,
     * not part of the on-wire protocol.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",      id)
        put("type",    type)
        put("payload", payload)
        put("src",     src)
        put("dst",     dst)
        put("hops",    hops)
        put("ttl",     ttl)
        put("ts",      ts)
    }

    /** Convenience: check whether this packet has expired (no hops left). */
    fun isExpired(): Boolean = ttl <= 0

    /** Convenience: check whether this packet targets all peers. */
    fun isBroadcast(): Boolean = dst == "*"

    /** Returns a copy with hops incremented and ttl decremented — use when relaying. */
    fun relayed(): MeshPacket = copy(hops = hops + 1, ttl = ttl - 1)
}
