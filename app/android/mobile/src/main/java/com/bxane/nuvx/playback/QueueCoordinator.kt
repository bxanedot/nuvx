package com.bxane.nuvx.playback

import androidx.media3.common.Player
import com.bxane.nuvx.data.listentogether.PartyTrack
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song

private fun Player.queueTierAt(index: Int): QueueTier = getMediaItemAt(index).queueTier

object QueueCoordinator {

    fun Song.asQueueEntry(tier: QueueTier): Song = with(QueueTimeline) { asQueueEntry(tier) }

    fun buildContextQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        newContextSongs: List<Song>,
        selectedIndex: Int,
        contextSource: QueueSource,
    ): ContextQueueResult = QueueTimeline.buildContextQueue(
        currentTimeline, currentIndex, newContextSongs, selectedIndex, contextSource,
    )

    fun buildOneOffQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        tappedSong: Song,
        source: QueueSource,
    ): List<Song> = QueueTimeline.buildOneOffQueue(currentTimeline, currentIndex, tappedSong, source)

    fun buildPartyPlaybackQueue(
        tappedSong: Song,
        source: QueueSource,
        upcomingPartyTracks: List<PartyTrack>,
    ): List<Song> = QueueTimeline.buildPartyPlaybackQueue(
        tappedSong,
        source,
        upcomingPartyTracks.map { it.toSong() },
    )

    fun findUserQueueInsertionIndex(
        timeline: List<Song>,
        currentIndex: Int,
        isNext: Boolean,
    ): Int = QueueTimeline.findUserQueueInsertionIndex(timeline, currentIndex, isNext)

    fun clearUserQueue(player: Player, tierAt: (Int) -> QueueTier = player::queueTierAt) {
        val currentIndex = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (count == 0) return

        val userQueueIndices = mutableListOf<Int>()
        for (i in (currentIndex + 1) until count) {
            if (tierAt(i) == QueueTier.USER_QUEUE) {
                userQueueIndices.add(i)
            }
        }

        player.removeRuns(userQueueIndices)
    }

    private fun Player.removeRuns(indices: List<Int>) {
        var end = indices.size
        while (end > 0) {
            var start = end - 1
            while (start > 0 && indices[start - 1] == indices[start] - 1) start--
            removeMediaItems(indices[start], indices[end - 1] + 1)
            end = start
        }
    }

    fun consumePlayedUserQueue(player: Player, tierAt: (Int) -> QueueTier = player::queueTierAt) {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex <= 0) return

        if (player.currentMediaItem == null) return
        if (tierAt(currentIndex) != QueueTier.CONTEXT) return

        val playedIndices = mutableListOf<Int>()
        for (i in 0 until currentIndex) {
            if (tierAt(i) == QueueTier.USER_QUEUE) {
                playedIndices.add(i)
            }
        }

        player.removeRuns(playedIndices)
    }

    fun buildJumpQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        targetIndex: Int,
    ): List<Song>? = QueueTimeline.buildJumpQueue(currentTimeline, currentIndex, targetIndex)

    fun jumpToQueueItem(
        player: Player,
        targetIndex: Int,
        cachedTimeline: List<Song>? = null,
        tierAt: (Int) -> QueueTier = player::queueTierAt,
        positionMs: Long = 0L,
        play: Boolean = true,
    ) {
        val currentIndex = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (targetIndex !in 0 until count) return

        val tiers = cachedTimeline?.takeIf { it.size == count }?.map { it.queueTier }
            ?: (0 until count).map { tierAt(it) }
        val plan = QueueTimeline.computeJumpOrder(tiers, currentIndex, targetIndex) ?: return

        if (plan.isPureSeek) {
            player.seekTo(plan.newTargetIndex, positionMs)
            if (play) player.play()
            return
        }

        val existingItems = (0 until count).map { player.getMediaItemAt(it) }
        val newItems = plan.newOrderIndices.map { idx ->
            val item = existingItems[idx]
            if (idx == targetIndex && plan.promoteTargetToContext) {
                QueueShuffle.withQueueMetadata(item, newTier = QueueTier.CONTEXT)
            } else {
                item
            }
        }

        val historyKept = plan.newOrderIndices.take(currentIndex + 1) == (0..currentIndex).toList()
        if (historyKept) {
            QueueShuffle.replaceChanged(player, currentIndex + 1, count, newItems.drop(currentIndex + 1))
            player.seekTo(plan.newTargetIndex, positionMs)
        } else {
            player.setMediaItems(newItems, plan.newTargetIndex, positionMs)
        }
        if (runCatching { player.playbackState }.getOrNull() == Player.STATE_IDLE) {
            player.prepare()
        }
        if (play) player.play()
    }
}
