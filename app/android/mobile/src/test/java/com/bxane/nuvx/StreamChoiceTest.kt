package com.bxane.nuvx

import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StreamChoiceTest {

    private fun stream(host: String) = SourceStream(
        url = "https://$host/track.mp4",
        format = StreamFormat(codec = "mp4", kbps = 320),
    )

    @Before
    @After
    fun reset() {
        (0..80).forEach { StreamChoice.forget("track-$it") }
    }

    @Test
    fun `a remembered choice is handed back`() {
        StreamChoice.remember("track-1", stream("aac.saavncdn.com"), substituted = true)
        assertEquals("https://aac.saavncdn.com/track.mp4", StreamChoice.of("track-1")?.url)
    }

    @Test
    fun `a track nothing has chosen for is free to resolve`() {
        assertNull(StreamChoice.of("track-2"))
    }

    @Test
    fun `remembers whether the copy came from a substitute`() {
        StreamChoice.remember("track-3", stream("aac.saavncdn.com"), substituted = true)
        StreamChoice.remember("track-4", stream("googlevideo.com"), substituted = false)
        assertEquals(true, StreamChoice.isSubstitute("track-3"))
        assertEquals(false, StreamChoice.isSubstitute("track-4"))
    }

    @Test
    fun `overflow drops the oldest choice rather than all of them`() {
        repeat(40) { StreamChoice.remember("track-$it", stream("host-$it.example"), substituted = true) }

        assertNotNull(StreamChoice.of("track-39"))
        assertEquals("https://host-39.example/track.mp4", StreamChoice.of("track-39")?.url)

        assertNotNull(
            "a recent choice was evicted, so overflow is still emptying the map wholesale",
            StreamChoice.of("track-20"),
        )
        assertEquals("https://host-20.example/track.mp4", StreamChoice.of("track-20")?.url)
    }

    @Test
    fun `forgetting a choice reopens the question`() {
        StreamChoice.remember("track-5", stream("aac.saavncdn.com"), substituted = true)
        StreamChoice.forget("track-5")
        assertNull(StreamChoice.of("track-5"))
    }
}
