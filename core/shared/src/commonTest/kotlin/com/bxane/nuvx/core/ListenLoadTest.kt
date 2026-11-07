package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenLoadTest {
    private class RecordingPlayer : NuvxPlayer {
        override val state = MutableStateFlow(PlayerState())
        val starts = mutableListOf<PlayerState>()
        override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
            state.value = PlayerState(tracks, startIndex, isPlaying = autoplay, positionMs = initialPositionMs)
            starts += state.value
        }
        override fun enqueue(tracks: List<Track>) = error("Unexpected enqueue")
        override fun togglePlay() = error("Initial load must not toggle play separately")
        override fun seekTo(positionMs: Long) = error("Initial load must not seek separately")
        override fun next() = Unit
        override fun previous() = Unit
        override fun skipTo(index: Int) = Unit
    }

    @Test fun pausedRoomLoadsQueuePositionAndPauseInOnePlayerRequest() {
        val player = RecordingPlayer()
        val shared = SharedPlayback("aaaaaaaaaaa", 12_000, false, 1_000,
            title = "Current", queue = listOf("bbbbbbbbbbb"))
        ListenSync.apply(player, ListenSync.decide(player.state.value, shared, 4_000))
        assertEquals(1, player.starts.size)
        val first = player.starts.single()
        assertEquals(false, first.isPlaying)
        assertEquals(12_000, first.positionMs)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), first.queue.map(Track::videoId))
    }

    @Test fun playingRoomStartsAtTheClockAdjustedPosition() {
        val player = RecordingPlayer()
        val shared = SharedPlayback("aaaaaaaaaaa", 12_000, true, 1_000)
        ListenSync.apply(player, ListenSync.decide(player.state.value, shared, 4_000))
        assertEquals(1, player.starts.size)
        assertEquals(true, player.starts.single().isPlaying)
        assertEquals(15_000, player.starts.single().positionMs)
    }
}
