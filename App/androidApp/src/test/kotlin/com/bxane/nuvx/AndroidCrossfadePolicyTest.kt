package com.bxane.nuvx

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCrossfadePolicyTest {
    @Test
    fun repeatOneAndInvalidOrSingleItemQueuesHaveNoNextDeck() {
        assertEquals(1, AndroidCrossfadePolicy.nextTrackIndex(0, 3, RepeatMode.OFF))
        assertEquals(null, AndroidCrossfadePolicy.nextTrackIndex(2, 3, RepeatMode.OFF))
        assertEquals(null, AndroidCrossfadePolicy.nextTrackIndex(0, 1, RepeatMode.ALL))
        assertEquals(null, AndroidCrossfadePolicy.nextTrackIndex(0, 3, RepeatMode.ONE))
        assertEquals(null, AndroidCrossfadePolicy.nextTrackIndex(-1, 3, RepeatMode.ALL))
    }

    @Test
    fun repeatAllWrapsToTheFirstTrack() {
        assertEquals(0, AndroidCrossfadePolicy.nextTrackIndex(2, 3, RepeatMode.ALL))
    }

    @Test
    fun onlySongToSongTransitionsAreEligible() {
        assertTrue(AndroidCrossfadePolicy.isEligible(MediaKind.SONG, MediaKind.SONG, RepeatMode.OFF))
        assertFalse(AndroidCrossfadePolicy.isEligible(MediaKind.VIDEO, MediaKind.SONG, RepeatMode.OFF))
        assertFalse(AndroidCrossfadePolicy.isEligible(MediaKind.SONG, MediaKind.VIDEO, RepeatMode.OFF))
        assertFalse(AndroidCrossfadePolicy.isEligible(MediaKind.SONG, MediaKind.SONG, RepeatMode.ONE))
    }

    @Test
    fun progressUsesMediaPositionAndClampsOutsideTheFadeWindow() {
        assertEquals(0f, AndroidCrossfadePolicy.progress(7_000, 7_000, 3_000), 0f)
        assertEquals(0.5f, AndroidCrossfadePolicy.progress(8_500, 7_000, 3_000), 0.0001f)
        assertEquals(1f, AndroidCrossfadePolicy.progress(10_000, 7_000, 3_000), 0f)
        assertEquals(0f, AndroidCrossfadePolicy.progress(6_000, 7_000, 3_000), 0f)
        assertEquals(0f, AndroidCrossfadePolicy.progress(8_000, 7_000, 0), 0f)
    }
}
