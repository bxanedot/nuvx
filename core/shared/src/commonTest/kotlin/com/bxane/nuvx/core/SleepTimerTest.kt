package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {
    private class Playing : NuvxPlayer {
        override val state = MutableStateFlow(PlayerState(listOf(Track("a", "a"), Track("b", "b")), index = 0, isPlaying = true))
        override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) = Unit
        override fun enqueue(tracks: List<Track>) = Unit
        override fun togglePlay() { state.value = state.value.copy(isPlaying = !state.value.isPlaying) }
        override fun seekTo(positionMs: Long) = Unit
        override fun next() { state.value = state.value.copy(index = state.value.index + 1) }
        override fun previous() = Unit
        override fun skipTo(index: Int) = Unit
    }

    @Test fun pausesAfterTheChosenTime() = runTest {
        val player = Playing()
        val timer = SleepTimer(player, backgroundScope)
        timer.set(SleepTimer.Mode.After(15))
        advanceTimeBy(15 * 60_000L - 1); runCurrent()
        assertTrue(player.state.value.isPlaying)
        advanceTimeBy(2); runCurrent()
        assertFalse(player.state.value.isPlaying)
        assertNull(timer.mode.value)
    }

    @Test fun pausesWhenTheTrackChangesAndCanBeCancelled() = runTest {
        val player = Playing()
        val timer = SleepTimer(player, backgroundScope)
        timer.set(SleepTimer.Mode.EndOfTrack); runCurrent()
        assertEquals(SleepTimer.Mode.EndOfTrack, timer.mode.value)
        // The next track buffers first (not playing), then starts; the timer pauses it once it starts.
        player.state.value = player.state.value.copy(index = 1, isPlaying = false); runCurrent()
        assertEquals(SleepTimer.Mode.EndOfTrack, timer.mode.value)
        player.state.value = player.state.value.copy(isPlaying = true); runCurrent()
        assertFalse(player.state.value.isPlaying)

        player.togglePlay()
        timer.set(SleepTimer.Mode.After(1)); timer.set(null)
        advanceTimeBy(120_000); runCurrent()
        assertTrue(player.state.value.isPlaying, "a cancelled timer never pauses")
    }

    @Test fun pausesWhenRepeatOneRestartsTheSameTrackAfterItsEnd() = runTest {
        val player = Playing()
        player.state.value = player.state.value.copy(repeat = RepeatMode.ONE)
        val timer = SleepTimer(player, backgroundScope)
        timer.set(SleepTimer.Mode.EndOfTrack); runCurrent()

        player.state.value = player.state.value.copy(positionMs = 89_500, durationMs = 90_000); runCurrent()
        assertTrue(player.state.value.isPlaying)
        player.state.value = player.state.value.copy(positionMs = 0); runCurrent()

        assertFalse(player.state.value.isPlaying)
        assertNull(timer.mode.value)
    }
}
