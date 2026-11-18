package com.licht.nuvx.data.innertube

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamResolverExitTest {
    @Test
    fun exitLevelRefusalsAreRecognisedEvenWhenWrapped() {
        assertTrue(StreamResolver.isExitRefusal(ContentNotAvailableException("Got error UNPLAYABLE: \"This video is not available\"")))
        assertTrue(StreamResolver.isExitRefusal(IOException("load failed", ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm you're not a bot\""))))
        assertTrue(StreamResolver.isExitRefusal(ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com/")))
    }

    @Test
    fun songLevelAndNetworkFailuresAreNot() {
        assertFalse(StreamResolver.isExitRefusal(ContentNotAvailableException("This video is private")))
        assertFalse(StreamResolver.isExitRefusal(IOException("timeout")))
        assertFalse(StreamResolver.isExitRefusal(IllegalStateException("Track unavailable: no audio streams")))
    }
}
