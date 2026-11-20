package com.licht.nuvx.ui.player

import com.metrolist.innertubex.extraction.StreamResolveException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoSyncTest {
    @Test
    fun pausedEndedVideoResumesOnBackSeekButNotAtItsActualDuration() {
        val fullDuration = 2L * 60 * 60 * 1000 // two-hour video, no preview cap
        assertTrue(musicVideoShouldResumeAfterEnd(30_000L, fullDuration))
        assertTrue(musicVideoShouldResumeAfterEnd(90L * 60 * 1000, fullDuration))
        assertFalse(musicVideoShouldResumeAfterEnd(fullDuration, fullDuration))
        assertFalse(musicVideoShouldResumeAfterEnd(fullDuration - 100, fullDuration))
        assertFalse(musicVideoShouldResumeAfterEnd(-1, fullDuration))
        assertFalse(musicVideoShouldResumeAfterEnd(1_000, -1))
    }

    @Test
    fun unavailableVideoIsNotRetriedForeverButNetworkFailuresCanBeRetried() {
        assertTrue(musicVideoCanRetry(StreamResolveException.Reason.NETWORK))
        assertFalse(musicVideoCanRetry(StreamResolveException.Reason.AGE_RESTRICTED))
        assertFalse(musicVideoCanRetry(StreamResolveException.Reason.NO_PLAYABLE_STREAM))
    }

    @Test
    fun seekThresholdDoesNotExceed50Milliseconds() {
        assertFalse(musicVideoNeedsSeek(50L))
        assertTrue(musicVideoNeedsSeek(51L))
        assertTrue(musicVideoNeedsSeek(-51L))
        assertTrue(musicVideoNeedsSeek(Long.MIN_VALUE))
    }
}
