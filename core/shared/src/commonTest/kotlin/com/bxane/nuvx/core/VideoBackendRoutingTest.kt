package com.bxane.nuvx.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VideoBackendRoutingTest {
    private class Backend(override val id: BackendId, val failure: Throwable? = null, val badAddress: Boolean = false) : MusicBackend, VideoCapableBackend {
        var calls = 0
        var height: Int? = null
        override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
        override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
        override suspend fun stream(videoId: String): AudioStream? = null
        override suspend fun video(videoId: String, maxHeight: Int?): VideoStream {
            calls++
            height = maxHeight
            failure?.let { throw it }
            return VideoStream(videoId, "https://r1.googlevideo.com/audio", if (badAddress) "http://127.0.0.1/private" else "https://r1.googlevideo.com/video",
                emptyMap(), 1280, 720, "video/mp4", "audio/mp4", "avc1", listOf(720), id)
        }
    }

    @Test fun selectedVideoEngineAndQualityCapAreHonored() = runTest {
        val primary = Backend(BackendId.INNERTUBEX)
        val alternative = Backend(BackendId.EXCOR)
        val router = BackendRouter(mapOf(primary.id to primary, alternative.id to alternative), BackendId.EXCOR, fallback = false)
        assertEquals(BackendId.EXCOR, router.resolveVideo("abcdefghijk", 360).backend)
        assertEquals(360, alternative.height)
        assertEquals(0, primary.calls)
        assertEquals(true, router.health.value[BackendId.EXCOR]?.ok)
    }

    @Test fun failedPrimaryCanUseAlternativeWithoutExposingItsError() = runTest {
        val primary = Backend(BackendId.INNERTUBEX, AssertionError("private signed-address fixture"))
        val alternative = Backend(BackendId.EXCOR)
        val router = BackendRouter(mapOf(primary.id to primary, alternative.id to alternative))
        assertEquals(BackendId.EXCOR, router.resolveVideo("abcdefghijk").backend)
        assertEquals(false, router.health.value[primary.id]?.ok)
        assertFalse(router.health.value[primary.id]?.detail.orEmpty().contains("signed-address"))
        router.setFallback(false)
        val error = assertFailsWith<BackendFailure> { router.resolveVideo("abcdefghijk") }
        assertFalse(error.toString().contains("signed-address"))
        assertFalse(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("signed-address") })
        assertEquals(1, alternative.calls)
    }

    @Test fun cancelledVideoDoesNotTryAnotherEngine() = runTest {
        val selected = Backend(BackendId.EXCOR, CancellationException("cancelled"))
        val other = Backend(BackendId.INNERTUBEX)
        val router = BackendRouter(mapOf(selected.id to selected, other.id to other), BackendId.EXCOR)
        assertFailsWith<CancellationException> { router.resolveVideo("abcdefghijk") }
        assertEquals(0, other.calls)
    }

    @Test fun invalidSourcesAreRejectedBeforeRecordingSuccessOrTryingPlayback() = runTest {
        val primary = Backend(BackendId.INNERTUBEX, badAddress = true)
        val alternative = Backend(BackendId.EXCOR)
        val router = BackendRouter(mapOf(primary.id to primary, alternative.id to alternative))
        assertEquals(BackendId.EXCOR, router.resolveVideo("abcdefghijk").backend)
        assertEquals(false, router.health.value[primary.id]?.ok)
        router.setFallback(false)
        assertFailsWith<BackendFailure> { router.resolveVideo("abcdefghijk") }
        assertEquals(1, alternative.calls)
    }
}
