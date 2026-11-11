package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether

internal class PlaybackQueuePersistenceController(
    private val boundedQueue: BoundedQueueController,
    private val queueStart: () -> Int,
    private val setQueueStart: (Int) -> Unit,
) {
    fun saveQueueSnapshot(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            setQueueStart(0)
            LastPlayed.clear()
            BoundedContextPersistence.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        setQueueStart(range.first)
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        boundedQueue.saveSnapshot(player)
        savePlaybackState(player)
    }

    fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        if (ListenTogether.state.value.inParty) return
        if (player.mediaItemCount == 0) {
            setQueueStart(0)
            LastPlayed.clearImmediately()
            BoundedContextPersistence.clearImmediately()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        setQueueStart(range.first)
        LastPlayed.saveQueueImmediately(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
        boundedQueue.saveSnapshot(player, immediately = true)
    }

    fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        boundedQueue.savePlaybackState(player)
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - queueStart(),
            positionMs = player.currentPosition,
        )
    }

    fun restoreLastQueue(player: ExoPlayer): Boolean {
        val last = LastPlayed.load() ?: return false
        setQueueStart(0)
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    fun restoreBoundedQueueOrLast(player: ExoPlayer): Boolean {
        val persisted = BoundedContextPersistence.load()
        if (persisted != null && boundedQueue.restore(player, persisted)) return true
        if (persisted != null) BoundedContextPersistence.clearImmediately()
        return restoreLastQueue(player)
    }
}
