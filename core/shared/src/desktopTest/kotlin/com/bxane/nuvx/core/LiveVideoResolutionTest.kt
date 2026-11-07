package com.bxane.nuvx.core

import com.excor.Excor
import com.excor.ExcorVideoCodec
import com.excor.excorDefaultHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            throw AssertionError("Video extraction reason=${error.reason.name}; attempts=${error.diagnostics?.attempts?.size ?: 0}")
        }
        assertNotNull(stream, "No compatible video stream was returned")
        assertTrue(isSupportedMediaAddress(stream.audioUrl), "Audio stream address is unsupported")
        assertTrue(isSupportedMediaAddress(stream.videoUrl), "Video stream address is unsupported")
        assertTrue(stream.width != null && stream.height != null, "Video dimensions are missing")
    }

    @Test fun excorResolvesVideoWithCompatibleAudio() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact YouTube", System.getenv("NUVX_LIVE") == "1")
        val stream = try {
            withTimeout(60_000) {
                Excor(excorDefaultHost()).extractVideo("dQw4w9WgXcQ", maxHeight = 720,
                    codecs = setOf(ExcorVideoCodec.H264))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AssertionError("eXcor video extraction failed: ${error::class.simpleName}")
        }
        assertNotNull(stream, "eXcor returned no playable video")
        assertTrue(isSupportedMediaAddress(stream.video.url), "Video stream address is unsupported")
        assertTrue(stream.video.height in 1..720, "Video height exceeds the requested limit")
        assertTrue(stream.video.containsAudio || stream.audio != null, "Video has no companion or embedded audio")
        stream.audio?.let { assertTrue(isSupportedMediaAddress(it.url), "Audio stream address is unsupported") }
        println("eXcor video resolution=${stream.video.width}x${stream.video.height}; embeddedAudio=${stream.video.containsAudio}; companionAudio=${stream.audio != null}")
    }
}
