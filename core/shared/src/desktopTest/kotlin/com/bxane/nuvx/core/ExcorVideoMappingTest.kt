package com.bxane.nuvx.core

import com.excor.*
import kotlin.test.*

class ExcorVideoMappingTest {
    private val headers = mapOf("User-Agent" to "fixture-agent")
    private val audio = ExcorStream("abcdefghijk", "https://r1.googlevideo.com/audio", 160, "audio/mp4", null,
        "fixture", "fixture", headers)
    private val picture = ExcorVideoTrack("https://r1.googlevideo.com/video", "video/mp4", ExcorVideoCodec.H264,
        1280, 720, 30, "720p", 1000, null, null, null, false, headers)
    private val streams = ExcorVideoStreams("abcdefghijk", picture, audio, "fixture", "fixture")

    @Test fun mapsRealTrackIdentityHeadersCodecAndOnlyTheObservedQuality() {
        val result = assertNotNull(streams.toNuvxVideoStream())
        assertEquals("abcdefghijk", result.videoId)
        assertEquals(audio.url, result.audioUrl)
        assertEquals(picture.url, result.videoUrl)
        assertEquals(headers, result.headers)
        assertEquals(BackendId.EXCOR, result.backend)
        assertEquals("avc1", result.codecs)
        assertEquals("audio/mp4", result.audioMimeType)
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertEquals(listOf(720), result.availableHeights)
        assertFalse(result.toString().contains("fixture-agent"))
    }

    @Test fun rejectsMismatchedCompanionIdentityOrHeaders() {
        assertFailsWith<IllegalArgumentException> { streams.copy(audio = audio.copy(videoId = "different01")).toNuvxVideoStream() }
        assertFailsWith<IllegalArgumentException> { streams.copy(audio = audio.copy(headers = mapOf("User-Agent" to "other"))).toNuvxVideoStream() }
    }

    @Test fun progressiveOnlyCannotMasqueradeAsTwoAdaptiveTracks() {
        assertNull(streams.copy(video = picture.copy(containsAudio = true), audio = null).toNuvxVideoStream())
        assertNull(streams.copy(audio = null).toNuvxVideoStream())
    }

    @Test fun validatesDimensionsAndMapsTheReportedCodec() {
        assertFailsWith<IllegalArgumentException> { streams.copy(video = picture.copy(height = 0)).toNuvxVideoStream() }
        assertEquals("av01", streams.copy(video = picture.copy(codec = ExcorVideoCodec.AV1)).toNuvxVideoStream()?.codecs)
    }
}
