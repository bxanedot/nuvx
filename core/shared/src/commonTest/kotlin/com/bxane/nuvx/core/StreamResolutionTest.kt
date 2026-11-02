package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class StreamResolutionTest {
    private val id = "dQw4w9WgXcQ"
    private fun router(url: String, headers: Map<String, String> = emptyMap()): BackendRouter {
        val backend = object : MusicBackend {
            override val id = BackendId.INNERTUBEX
            override suspend fun search(query: String) = SearchResults(emptyList(), emptyList())
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String) = AudioStream(videoId, url, headers, "audio/webm", 128, null, id)
        }
        return BackendRouter(mapOf(backend.id to backend))
    }

    @Test fun keepsStreamHeadersAndSignedQuery() = runTest {
        val headers = mapOf("User-Agent" to "nuvx", "Referer" to "https://music.youtube.com/")
        val stream = router("https://r1.googlevideo.com/videoplayback?sig=abc", headers).resolveAudio(id)
        assertEquals("https://r1.googlevideo.com/videoplayback?sig=abc", stream.url)
        assertSame(headers, stream.headers)
    }

    @Test fun rejectsUntrustedDestinationsWithoutExposingUrl() = runTest {
        for (url in listOf("http://r1.googlevideo.com/a", "https://127.0.0.1/a", "file:///private", "https://googlevideo.com.evil.test/a", "https://secret@r1.googlevideo.com/a", "https://r1.googlevideo.com:8443/a")) {
            val error = assertFailsWith<IllegalArgumentException> { router(url).resolveAudio(id) }
            assertEquals("The engine returned an unsupported media address", error.message)
        }
    }

    @Test fun rejectsInvalidTrackBeforeCallingEngine() = runTest {
        assertFailsWith<IllegalArgumentException> { router("https://r1.googlevideo.com/a").resolveAudio("../bad") }
    }
}
