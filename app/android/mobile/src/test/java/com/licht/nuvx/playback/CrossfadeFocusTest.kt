package com.licht.nuvx.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFocusTest {
    @Test
    fun `focus regain never resumes a manually paused or missing owner`() {
        assertTrue(followerMayPlay(ownerPlayWhenReady = true, focusHeld = false))
        assertFalse(followerMayPlay(ownerPlayWhenReady = true, focusHeld = true))
        assertFalse(followerMayPlay(ownerPlayWhenReady = false, focusHeld = false))
        assertFalse(followerMayPlay(ownerPlayWhenReady = false, focusHeld = true))
        assertFalse(followerMayPlay(ownerPlayWhenReady = null, focusHeld = false))
    }

    @Test
    fun `only a transient focus loss holds the blend`() {
        assertTrue(heldByTransientFocusLoss(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
        assertFalse(heldByTransientFocusLoss(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertFalse(heldByTransientFocusLoss(Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT))
    }
}
