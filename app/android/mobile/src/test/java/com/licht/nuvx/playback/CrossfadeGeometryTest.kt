package com.licht.nuvx.playback

import com.licht.nuvx.data.settings.MixBlend
import com.licht.nuvx.playback.smart.TrackAnalysis
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Characterization: pins today's crossfade timing math so a refactor cannot change it silently.
class CrossfadeGeometryTest {
    @Test
    fun plainFadeEndsWhereTheOutgoingTrackFades() {
        val g = CrossfadeGeometry.playback(fadeEndMs = 200_000, fadeMs = 8_000, advanced = false, mediaRatio = 1.2, incomingCueTimeMs = 0)
        assertEquals(192.0, g.outStart, 1e-9)
        assertEquals(200.0, g.outEnd, 1e-9)
        assertEquals(1.0, g.ratio, 1e-9) // speed matching only in advanced mixes
        assertEquals(8.0, g.inEnd - g.inStart, 1e-9)
    }

    @Test
    fun advancedFadeStretchesTheIncomingSideByTheSpeedRatio() {
        val g = CrossfadeGeometry.playback(fadeEndMs = 60_000, fadeMs = 10_000, advanced = true, mediaRatio = 1.5, incomingCueTimeMs = 4_000)
        assertEquals(4.0, g.inStart, 1e-9)
        assertEquals(19.0, g.inEnd, 1e-9)
    }

    @Test
    fun aFadeIsNeverShorterThanHalfASecond() {
        val g = CrossfadeGeometry.playback(fadeEndMs = 10_000, fadeMs = 0, advanced = false, mediaRatio = 1.0, incomingCueTimeMs = 0)
        assertEquals(0.5, g.outSpan, 1e-9)
    }

    @Test
    fun beatMarksFollowTheDownbeatGrid() {
        val analysis = TrackAnalysis(downbeats = listOf(9.75))
        val marks = CrossfadeGeometry.beatMarks(analysis, outStart = 10.0, outEnd = 12.0, beatSeconds = 0.5)
        assertArrayEquals(floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f), marks, 1e-6f)
        assertEquals(0, CrossfadeGeometry.beatMarks(analysis, 10.0, 12.0, 0.0).size)
    }

    @Test
    fun sameGridToleratesSmallDrift() {
        val last = MixBlend(beatMs = 500f, beatAnchorNanos = 0L, playing = true)
        assertTrue(CrossfadeGeometry.sameGrid(last, 500f, 1_000_000_000L + 20_000_000L))
        assertFalse(CrossfadeGeometry.sameGrid(last, 500f, 1_000_000_000L + 100_000_000L))
        assertFalse(CrossfadeGeometry.sameGrid(last, 520f, 0L))
        assertTrue(CrossfadeGeometry.sameGrid(last.copy(beatMs = 0f), 0f, 123L))
    }
}
