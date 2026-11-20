package com.licht.nuvx.wear.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSkipPolicyTest {
    @Test
    fun skipCommandsRequireAnAvailableTrackAndLivePhone() {
        assertTrue(canIssueSkip(true, Link.Connected))
        assertFalse(canIssueSkip(false, Link.Connected))
        assertFalse(canIssueSkip(true, Link.Connecting))
        assertFalse(canIssueSkip(true, Link.NoPhone))
        assertFalse(canIssueSkip(false, Link.NoPhone))
    }

    @Test
    fun reconnectNeedsAFreshCapabilityReportBeforeSkip() {
        // A cached NowPlaying snapshot may still have hasNext=true after the
        // phone goes away. The reconnect UI must not issue commands to it.
        val staleHasNext = true
        assertFalse(canIssueSkip(staleHasNext, Link.NoPhone))
        assertFalse(canIssueSkip(staleHasNext, Link.Connecting))
        assertTrue(canIssueSkip(staleHasNext, Link.Connected))
    }
}
