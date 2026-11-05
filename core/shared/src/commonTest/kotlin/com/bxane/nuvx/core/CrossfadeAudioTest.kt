package com.bxane.nuvx.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeAudioTest {
    @Test
    fun equalPowerLevelsStartAndEndOnTheExpectedDeck() {
        assertEquals(CrossfadeAudio.Levels(outgoing = 1f, incoming = 0f), CrossfadeAudio.levels(0f))
        assertEquals(CrossfadeAudio.Levels(outgoing = 0f, incoming = 1f), CrossfadeAudio.levels(1f))
    }

    @Test
    fun equalPowerLevelsKeepConstantCombinedPowerAndClampProgress() {
        for (progress in listOf(-1f, 0.25f, 0.5f, 0.75f, 2f)) {
            val levels = CrossfadeAudio.levels(progress)
            assertTrue(abs(levels.outgoing * levels.outgoing + levels.incoming * levels.incoming - 1f) < 0.0001f)
        }
        assertEquals(CrossfadeAudio.levels(0f), CrossfadeAudio.levels(-1f))
        assertEquals(CrossfadeAudio.levels(1f), CrossfadeAudio.levels(2f))
    }

    @Test
    fun transitionDurationDoesNotOutlastEitherTrack() {
        assertEquals(3_000L, CrossfadeAudio.transitionDurationMs(5, 3_000, 12_000))
        assertEquals(0L, CrossfadeAudio.transitionDurationMs(5, 0, 12_000))
        assertEquals(0L, CrossfadeAudio.transitionDurationMs(0, 3_000, 12_000))
    }
}
