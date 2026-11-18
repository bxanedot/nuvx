package com.licht.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun retriesTwiceWithGrowingDelayThenGivesUp() {
        assertFalse(RecoveryPolicy.givesUp(1, null))
        assertFalse(RecoveryPolicy.givesUp(2, null))
        assertTrue(RecoveryPolicy.givesUp(3, null))
        assertEquals(350L, RecoveryPolicy.retryDelayMs(1))
        assertEquals(700L, RecoveryPolicy.retryDelayMs(2))
    }

    @Test
    fun aPermanentFailureIsNeverRetried() {
        assertTrue(RecoveryPolicy.givesUp(1, "removed"))
        assertEquals("removed", RecoveryPolicy.skipReason("removed", "E", neverStarted = false, attempts = 1, skipsInARow = 9))
    }

    @Test
    fun skipsOnlyTracksThatNeverStartedAndStopsAfterFourInARow() {
        assertNull(RecoveryPolicy.skipReason(null, "E", neverStarted = false, attempts = 3, skipsInARow = 0))
        assertEquals(
            "failed to start after 3 attempts (E)",
            RecoveryPolicy.skipReason(null, "E", neverStarted = true, attempts = 3, skipsInARow = 3),
        )
        assertNull(RecoveryPolicy.skipReason(null, "E", neverStarted = true, attempts = 3, skipsInARow = 4))
    }

    @Test
    fun anUnresolvableGooglevideoHostIsRecognisedThroughWrappedCauses() {
        val dns = java.net.UnknownHostException("Unable to resolve host \"rr1---sn-4g5e6nzl.googlevideo.com\": No address associated with hostname")
        assertTrue(RecoveryPolicy.streamHostUnresolved(java.io.IOException("source error", java.io.IOException("open", dns))))
        assertFalse(RecoveryPolicy.streamHostUnresolved(java.net.UnknownHostException("Unable to resolve host \"music.youtube.com\"")))
        assertFalse(RecoveryPolicy.streamHostUnresolved(java.net.SocketTimeoutException("googlevideo.com timed out")))
        assertFalse(RecoveryPolicy.streamHostUnresolved(null))
    }
}
