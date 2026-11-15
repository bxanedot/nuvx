package com.bxane.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessGainTest {
    @Test
    fun defaultTargetCancelsYouTubeLoudness() {
        assertEquals(-300, loudnessGainMb(loudnessDb = 3.0, targetLufs = -14))
        assertEquals(200, loudnessGainMb(loudnessDb = -2.0, targetLufs = -14))
    }

    @Test
    fun quieterTargetLowersGain() {
        assertEquals(-500, loudnessGainMb(loudnessDb = 3.0, targetLufs = -16))
    }

    @Test
    fun gainStaysWithinSafeRange() {
        assertEquals(300, loudnessGainMb(loudnessDb = -12.0, targetLufs = -10))
        assertEquals(-1500, loudnessGainMb(loudnessDb = 20.0, targetLufs = -20))
    }
}
