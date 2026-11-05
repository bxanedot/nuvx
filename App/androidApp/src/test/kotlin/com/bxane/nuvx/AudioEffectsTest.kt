package com.bxane.nuvx

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioEffectsTest {
    @Test
    fun effectSessionFollowsPlaybackRequestAndClosesAtPauseOrEnd() {
        assertEquals(C.AUDIO_SESSION_ID_UNSET, activeAudioEffectSessionId(playWhenReady = false, playbackState = Player.STATE_READY, audioSessionId = 42))
        assertEquals(42, activeAudioEffectSessionId(playWhenReady = true, playbackState = Player.STATE_BUFFERING, audioSessionId = 42))
        assertEquals(C.AUDIO_SESSION_ID_UNSET, activeAudioEffectSessionId(playWhenReady = true, playbackState = Player.STATE_IDLE, audioSessionId = 42))
        assertEquals(C.AUDIO_SESSION_ID_UNSET, activeAudioEffectSessionId(playWhenReady = true, playbackState = Player.STATE_ENDED, audioSessionId = 42))
        assertEquals(C.AUDIO_SESSION_ID_UNSET, activeAudioEffectSessionId(playWhenReady = true, playbackState = Player.STATE_BUFFERING, audioSessionId = C.AUDIO_SESSION_ID_UNSET))
    }
}
