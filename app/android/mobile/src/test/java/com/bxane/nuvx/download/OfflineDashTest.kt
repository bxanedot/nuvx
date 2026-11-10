package com.bxane.nuvx.download

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDashTest {

    private fun rep(id: String, codecs: String, bandwidth: Int, last: Int) = """
        <Representation id="$id" codecs="$codecs" bandwidth="$bandwidth" audioSamplingRate="44100">
        <SegmentTemplate timescale="44100" initialization="https://cdn/$id/0.mp4?a=1&amp;b=2"
            media="https://cdn/$id/${'$'}Number${'$'}.mp4?a=1&amp;b=2" startNumber="1">
        <SegmentTimeline><S d="176128" r="2"/><S d="$last"/></SegmentTimeline></SegmentTemplate></Representation>
    """

    private val tidal = """<?xml version='1.0'?><MPD type="static" mediaPresentationDuration="PT12S"><Period id="0">
        <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4">
        ${rep("HEAACV1", "mp4a.40.5", 97002, 29960)}
        ${rep("AACLC", "mp4a.40.2", 321819, 26950)}
        ${rep("FLAC,44100,16", "flac", 900005, 24902)}
        </AdaptationSet></Period></MPD>"""

    @Test
    fun `keeps the flac rendition, not the first one listed`() {
        val plan = OfflineDash.parse(tidal)
        assertEquals("https://cdn/FLAC,44100,16/0.mp4?a=1&b=2", plan.initialization)
        assertEquals("https://cdn/FLAC,44100,16/4.mp4?a=1&b=2", plan.media.last())
        assertEquals(4, plan.media.size)
        assertEquals(24902 / 44100.0, plan.seconds.last(), 1e-9)
    }

    @Test
    fun `without a lossless rendition takes the highest bandwidth`() {
        val plan = OfflineDash.parse(tidal.replace(Regex("""<Representation id="FLAC.*?</Representation>""", RegexOption.DOT_MATCHES_ALL), ""))
        assertEquals("https://cdn/AACLC/0.mp4?a=1&b=2", plan.initialization)
    }
}
