package com.bxane.nuvx

import com.bxane.nuvx.download.OfflineDash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDashTest {

    private companion object {
        const val STARBOY = """<?xml version='1.0' encoding='UTF-8'?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" """ +
            """profiles="urn:mpeg:dash:profile:isoff-main:2011" type="static" minBufferTime="PT3.993S" """ +
            """mediaPresentationDuration="PT3M50.461S"><Period id="0"><AdaptationSet id="0" contentType="audio" """ +
            """mimeType="audio/mp4" lang="und" group="main" segmentAlignment="true">""" +
            """<Representation id="FLAC,44100,16" codecs="flac" bandwidth="928340" audioSamplingRate="44100">""" +
            """<SegmentTemplate timescale="44100" """ +
            """initialization="https://sp-ad-cf.audio.tidal.com/mediatracks/BLOB/0.mp4?Policy=P&amp;Signature=S" """ +
            """media="https://sp-ad-cf.audio.tidal.com/mediatracks/BLOB/${'$'}Number${'$'}.mp4?Policy=P&amp;Signature=S" """ +
            """startNumber="1"><SegmentTimeline><S d="176128" r="56"/><S d="124050"/></SegmentTimeline>""" +
            """</SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
    }

    @Test
    fun `a manifest is recognised by its extension, query string and all`() {
        assertTrue(OfflineDash.handles("https://im-cf.manifest.tidal.com/1/manifests/AbC.mpd?Expires=1&Signature=x"))
        assertTrue(OfflineDash.handles("https://host/a.MPD"))
        assertFalse(OfflineDash.handles("https://im-cf.manifest.tidal.com/1/manifests/AbC.m3u8?Expires=1"))
        assertFalse(OfflineDash.handles("https://aac.saavncdn.com/820/abc_320.mp4"))
    }

    @Test
    fun `the timeline expands to one entry per segment`() {
        val plan = OfflineDash.parse(STARBOY)
        assertEquals(58, plan.media.size)
        assertEquals(58, plan.seconds.size)

        assertEquals(3.994, plan.seconds.first(), 0.001)
        assertEquals(3.994, plan.seconds[56], 0.001)
        assertEquals(2.813, plan.seconds.last(), 0.001)

        assertEquals(230.461, plan.seconds.sum(), 0.01)
    }

    @Test
    fun `segment urls come off the template unescaped`() {
        val plan = OfflineDash.parse(STARBOY)
        assertEquals("https://sp-ad-cf.audio.tidal.com/mediatracks/BLOB/0.mp4?Policy=P&Signature=S", plan.initialization)
        assertEquals("https://sp-ad-cf.audio.tidal.com/mediatracks/BLOB/1.mp4?Policy=P&Signature=S", plan.media.first())
        assertEquals("https://sp-ad-cf.audio.tidal.com/mediatracks/BLOB/58.mp4?Policy=P&Signature=S", plan.media.last())
    }

    @Test
    fun `the package is written as an HLS playlist over the saved segments`() {
        val text = OfflineDash.playlist(OfflineDash.parse(STARBOY))
        val lines = text.trim().lines()

        assertEquals("#EXTM3U", lines.first())
        assertTrue("""#EXT-X-MAP:URI="segment-00000.m4s"""" in lines)
        assertTrue("#EXT-X-TARGETDURATION:4" in lines)
        assertEquals("#EXT-X-ENDLIST", lines.last())

        assertTrue("segment-00001.m4s" in lines)
        assertTrue("segment-00058.m4s" in lines)
        assertFalse("segment-00059.m4s" in lines)
        assertEquals(58, lines.count { it.startsWith("#EXTINF:") })
        assertTrue("#EXTINF:3.994," in lines)
        assertTrue("#EXTINF:2.813," in lines)
    }

    @Test
    fun `manifests that cannot be saved whole are refused`() {
        val encrypted = STARBOY.replace("<SegmentTemplate", """<ContentProtection schemeIdUri="urn:x"/><SegmentTemplate""")
        assertEquals(
            "Encrypted DASH cannot be saved",
            runCatching { OfflineDash.parse(encrypted) }.exceptionOrNull()?.message,
        )

        val twoPeriods = STARBOY.replace("</MPD>", """<Period id="1"></Period></MPD>""")
        assertEquals(
            "Multi-period DASH cannot be saved",
            runCatching { OfflineDash.parse(twoPeriods) }.exceptionOrNull()?.message,
        )

        val timeBased = STARBOY.replace("${'$'}Number${'$'}", "${'$'}Time${'$'}")
        assertEquals(
            "Unsupported DASH media template",
            runCatching { OfflineDash.parse(timeBased) }.exceptionOrNull()?.message,
        )
    }
}
