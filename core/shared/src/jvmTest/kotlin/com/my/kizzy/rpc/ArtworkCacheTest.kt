package com.my.kizzy.rpc

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkCacheTest {
    @Test
    fun hitsExpiryAndLruEvictionAreBounded() = runBlocking {
        var clock = 1_000L
        var calls = 0
        val cache = TimedArtworkCache(maxEntries = 2, ttlMs = 100, nowMs = { clock })
        suspend fun resolve(key: String): String? = cache.getOrFetch(key) {
            calls++
            "asset-$key-$calls"
        }
        assertEquals("asset-a-1", resolve("a"))
        assertEquals("asset-a-1", resolve("a"))
        assertEquals(1, calls, "fresh hits must avoid another network call")
        resolve("b")
        resolve("a") // a is now the most recently used
        resolve("c") // b is evicted
        assertEquals(2, cache.size())
        assertEquals("asset-a-1", resolve("a"))
        assertEquals("asset-b-4", resolve("b"))
        clock += 101
        assertEquals("asset-b-5", resolve("b"), "expired values are fetched again")
        cache.invalidate()
        assertEquals(0, cache.size())
    }

    @Test
    fun failedResolutionAndSignedUrlsAreNeverCached() = runBlocking {
        val cache = TimedArtworkCache(maxEntries = 1, ttlMs = 1000)
        var failures = 0
        assertNull(cache.getOrFetch("bad") {
            failures++
            throw IllegalStateException("unavailable")
        })
        assertNull(cache.getOrFetch("bad") {
            failures++
            null
        })
        assertEquals(2, failures, "a transient failure is never cached")
        var signedCalls = 0
        val signed = "https://cdn.invalid/image.jpg?token=private"
        repeat(2) {
            assertEquals("fallback", cache.getOrFetch(signed) {
                signedCalls++
                "fallback"
            })
        }
        assertEquals(2, signedCalls)
        assertEquals(0, cache.size(), "signed URL keys must not survive")
    }

    @Test
    fun cancellationIsNotSwallowedByTheImageFallback() = runBlocking {
        val cache = TimedArtworkCache()
        val cancelled = kotlinx.coroutines.CancellationException("left page")
        var propagated = false
        try {
            cache.getOrFetch("aborted") { throw cancelled }
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = e === cancelled
        }
        assertTrue(propagated, "caller must retain cancellation semantics")
        assertEquals(0, cache.size())
    }
}
