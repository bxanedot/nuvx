package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

@UnstableApi
class DynamicLruCacheEvictor(
    @Volatile var maxBytes: Long,
    private val headBytes: Long = DEFAULT_HEAD_BYTES,
    private val headBudgetBytes: Long = DEFAULT_HEAD_BUDGET_BYTES,
) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compare)

    private val protectedHeads = TreeSet<CacheSpan>(::compare)
    private var protectedSize = 0L
    private var currentSize = 0L

    private fun isHead(span: CacheSpan): Boolean = span.position < headBytes

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (isHead(span) && protectedSize + span.length <= headBudgetBytes) {
            protectedHeads.add(span)
            protectedSize += span.length
        } else {
            leastRecentlyUsed.add(span)
        }
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (protectedHeads.remove(span)) {
            protectedSize -= span.length
        } else {
            leastRecentlyUsed.remove(span)
        }
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun applyNow(cache: Cache) = evictCache(cache, 0)

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes) {
            if (leastRecentlyUsed.isNotEmpty()) {
                cache.removeSpan(leastRecentlyUsed.first())
                continue
            }
            val oldest = protectedHeads.pollFirst() ?: return
            protectedSize -= oldest.length
            leastRecentlyUsed.add(oldest)
        }
    }

    private companion object {
        const val DEFAULT_HEAD_BYTES = 4L * 1024 * 1024

        const val DEFAULT_HEAD_BUDGET_BYTES = 96L * 1024 * 1024

        fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
            val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
            return when {
                delta == 0L -> lhs.compareTo(rhs)
                delta < 0L -> -1
                else -> 1
            }
        }
    }
}
