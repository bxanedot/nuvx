package com.licht.nuvx.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class StreamResolverDownloadTest {
    @Test
    fun `rate limited extraction is not reported as missing audio`() {
        val refusal = ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com/")

        val error = StreamResolver.downloadResolutionFailure(offeredAudio = false, failure = refusal)

        assertEquals("YouTube temporarily refused this download. Wait a moment and retry.", error.message)
        assertSame(refusal, error.cause)
    }

    @Test
    fun `ordinary extraction failure keeps existing retry message`() {
        val error = StreamResolver.downloadResolutionFailure(offeredAudio = true, failure = IOException("offline"))

        assertEquals("Couldn't reach a downloadable copy just now — try again", error.message)
        assertEquals("offline", error.cause?.message)
    }
}
