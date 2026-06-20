package com.bnn.app.mesh

import java.util.Collections

/**
 * PacketDedup — thread-safe O(1) deduplication for mesh packets.
 *
 * Keeps the last [maxSize] message IDs in an insertion-ordered [LinkedHashMap].
 * When the map exceeds [maxSize] entries the oldest entry is automatically evicted,
 * giving an LRU-like sliding window without any explicit timer or coroutine.
 *
 * Typical usage in a relay loop:
 * ```
 * if (!dedup.isNew(packet.id)) return   // already seen, drop silently
 * relay(packet)
 * ```
 *
 * @param maxSize Maximum number of packet IDs to remember (default 2 000).
 */
class PacketDedup(private val maxSize: Int = 2000) {

    /**
     * Insertion-ordered map: id -> timestamp (ms) when first seen.
     *
     * [Collections.synchronizedMap] wraps it so individual [put] / [putIfAbsent]
     * calls are atomic on the internal monitor.  Callers that need compound
     * operations (e.g. iterate + remove) must additionally synchronise on [seen].
     */
    private val seen: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(maxSize, 0.75f, /* accessOrder = */ false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > maxSize
        }
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns **true** if [id] has NOT been seen before (novel packet).
     * Returns **false** if [id] is a duplicate — the caller should drop the packet.
     *
     * Blank / empty IDs always return true so that un-tagged packets are never
     * silently swallowed.
     */
    fun isNew(id: String): Boolean {
        if (id.isBlank()) return true
        // putIfAbsent is atomic; returns null only when the key was absent (→ novel).
        return seen.putIfAbsent(id, System.currentTimeMillis()) == null
    }

    /**
     * Explicitly mark an [id] as seen without checking novelty.
     * Useful when the local node generates a packet and wants to pre-seed
     * the dedup table so it never re-processes its own echoes.
     */
    fun markSeen(id: String) {
        if (id.isNotBlank()) seen[id] = System.currentTimeMillis()
    }

    /** Returns the number of IDs currently tracked. */
    fun size(): Int = seen.size

    /** Wipes all tracked IDs. Useful for test teardown or node resets. */
    fun clear() = seen.clear()
}
