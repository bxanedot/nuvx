package com.bxane.nuvx

import com.bxane.nuvx.playback.PlaybackPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionTest {
    @Test
    fun `live position interpolates between player samples`() {
        val position = PlaybackPosition().apply { report(10_000L, 1_000_000_000L) }

        assertEquals(10_250L, position.livePosition(true, 1_250_000_000L, 60_000L))
    }

    @Test
    fun `live position is capped at duration and interpolation limit`() {
        val position = PlaybackPosition().apply { report(10_000L, 1_000_000_000L) }

        assertEquals(10_100L, position.livePosition(true, 1_250_000_000L, 10_100L))
        assertEquals(11_500L, position.livePosition(true, 4_000_000_000L, 60_000L))
    }

    @Test
    fun `paused playback keeps the reported position`() {
        val position = PlaybackPosition().apply { report(10_000L, 1_000_000_000L) }

        assertEquals(10_000L, position.livePosition(false, 1_250_000_000L, 60_000L))
    }
}
