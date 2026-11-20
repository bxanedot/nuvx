package com.my.kizzy.rpc

import kotlin.coroutines.cancellation.CancellationException

/** Bounded and expiring cache for public Discord artwork-asset resolutions. */
internal class TimedArtworkCache(
    private val maxEntries: Int = 128,
    private val ttlMs: Long = 20 * 60_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(val asset: String, val atMs: Long)
    // Access order makes the oldest *unused* item the first eviction candidate.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        // A signed URL can contain credentials or an unbounded query string.
        // Never retain its key in this process-wide cache.
        val cacheable = key.isNotBlank() && key.length <= 2048 &&
            '?' !in key && '#' !in key
        if (cacheable) {
            synchronized(entries) {
                val entry = entries[key]
                if (entry != null && nowMs() - entry.atMs in 0 until ttlMs) return entry.asset
                entries.remove(key)
            }
        }

        val asset = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed resolver leaves the caller's existing artwork fallback intact.
            null
        } ?: return null

        if (!cacheable || maxEntries <= 0 || ttlMs <= 0 || asset.length > 4096) return asset
        synchronized(entries) {
            entries[key] = Entry(asset, nowMs())
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
        return asset
    }

    internal fun size(): Int = synchronized(entries) { entries.size }
    internal fun invalidate() = synchronized(entries) { entries.clear() }
}

internal object ArtworkCache {
    private val cache = TimedArtworkCache()
    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? =
        cache.getOrFetch(key, fetch)
}
