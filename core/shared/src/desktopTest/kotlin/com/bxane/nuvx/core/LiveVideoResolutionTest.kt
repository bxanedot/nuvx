package com.bxane.nuvx.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in network check: NUVX_LIVE=1. Never prints signed stream addresses or headers. */
class LiveVideoResolutionTest {
    @Test fun innerTubeXResolvesCompanionAudioAndVideo() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact YouTube", System.getenv("NUVX_LIVE") == "1")

        val backend = InnerTubeXBackend()
        val stream = try {
            backend.video("dQw4w9WgXcQ")
        } catch (error: com.metrolist.innertubex.extraction.StreamResolveException) {
            println("Video extraction reason=${error.reason}; attempts=${error.diagnostics?.attempts?.size ?: 0}; outcomes=${error.diagnostics?.attempts?.map { it.outcome }?.distinct().orEmpty()}")
            throw error
        }
        assertNotNull(stream, "No compatible video stream was returned")
        assertTrue(isSupportedMediaAddress(stream.audioUrl), "Audio stream address is unsupported")
        assertTrue(isSupportedMediaAddress(stream.videoUrl), "Video stream address is unsupported")
        assertTrue(stream.width != null && stream.height != null, "Video dimensions are missing")
    }
}
