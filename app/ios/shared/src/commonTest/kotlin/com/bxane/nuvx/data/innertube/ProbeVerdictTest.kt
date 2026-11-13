package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.http.ProbeResult
import kotlin.test.Test
import kotlin.test.assertEquals















class ProbeVerdictTest {

    private val muxed = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""
    private val audioOnly = "audio/mp4; codecs=\"mp4a.40.2\""

    private fun probe(
        status: Int,
        contentType: String? = null,
        bodyArrived: Boolean = true,
    ) = ProbeResult(status = status, contentType = contentType, bodyArrived = bodyArrived)



    @Test
    fun `a muxed format's own answer is playable`() {
        assertEquals(ProbeVerdict.OK, probe(206, "video/mp4").classify(muxed))
        assertEquals(ProbeVerdict.OK, probe(200, "video/mp4").classify(muxed))
    }

    @Test
    fun `an audio-only format's own answer is playable`() {
        assertEquals(ProbeVerdict.OK, probe(206, "audio/mp4").classify(audioOnly))
    }

    @Test
    fun `the codec list is not part of the comparison`() {


        assertEquals(ProbeVerdict.OK, probe(206, "video/mp4").classify(muxed))
        assertEquals(ProbeVerdict.OK, probe(206, "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"").classify(muxed))
    }

    @Test
    fun `a different family is a different file`() {



        assertEquals(ProbeVerdict.REFUSED, probe(206, "video/mp4").classify(audioOnly))
        assertEquals(ProbeVerdict.REFUSED, probe(206, "audio/mp4").classify(muxed))
    }



    @Test
    fun `an error page is refused whatever the format`() {


        assertEquals(ProbeVerdict.REFUSED, probe(200, "text/html; charset=utf-8").classify(muxed))
        assertEquals(ProbeVerdict.REFUSED, probe(200, "text/html").classify(audioOnly))
        assertEquals(ProbeVerdict.REFUSED, probe(200, "application/json").classify(audioOnly))
    }

    @Test
    fun `a refusal is the client's problem and not a bad minute`() {
        listOf(403, 404, 410).forEach { status ->
            assertEquals(ProbeVerdict.REFUSED, probe(status, "video/mp4").classify(muxed), "$status")
        }
    }

    @Test
    fun `no answer at all blames nobody in particular`() {
        assertEquals(ProbeVerdict.UNREACHABLE, probe(0, null, bodyArrived = false).classify(muxed))
        assertEquals(ProbeVerdict.UNREACHABLE, probe(503, null, bodyArrived = false).classify(muxed))
    }

    @Test
    fun `media that was asked for but never arrived is not playable`() {


        assertEquals(ProbeVerdict.UNREACHABLE, probe(206, "video/mp4", bodyArrived = false).classify(muxed))
    }

    @Test
    fun `a range past the end is the end and not a failure`() {


        assertEquals(ProbeVerdict.UNREACHABLE, probe(416, null, bodyArrived = false).classify(muxed))
    }

    @Test
    fun `a server that declines to name a type is not naming a wrong one`() {
        assertEquals(ProbeVerdict.OK, probe(206, "application/octet-stream").classify(muxed))
        assertEquals(ProbeVerdict.OK, probe(206, "application/octet-stream").classify(audioOnly))
    }



    @Test
    fun `with no format to compare against only the wrong answers are refused`() {


        assertEquals(ProbeVerdict.OK, probe(206, "video/mp4").classify())
        assertEquals(ProbeVerdict.OK, probe(206, "audio/mp4").classify())
        assertEquals(ProbeVerdict.REFUSED, probe(200, "text/html").classify())
        assertEquals(ProbeVerdict.REFUSED, probe(206, null).classify())
    }

    @Test
    fun `a content type with a charset is still a content type`() {
        assertEquals(ProbeVerdict.OK, probe(206, "video/mp4; charset=utf-8").classify(muxed))
        assertEquals(ProbeVerdict.OK, probe(206, "  VIDEO/MP4  ").classify(muxed))
    }
}
