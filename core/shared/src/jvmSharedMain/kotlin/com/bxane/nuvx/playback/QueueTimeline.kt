package com.bxane.nuvx.playback

import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song
import java.util.UUID

data class QueueSource(
    val title: String,
    val type: PlaybackSourceType,
    val id: String? = null,
)

data class ContextQueueResult(
    val timeline: List<Song>,
    val startIndex: Int,
)

data class JumpOrderPlan(
    val isPureSeek: Boolean,
    val newOrderIndices: List<Int>,
    val newTargetIndex: Int,
    val promoteTargetToContext: Boolean = false,
)

object QueueTimeline {

    fun Song.asQueueEntry(tier: QueueTier): Song = copy(
        queueTier = tier,
        queueEntryId = queueEntryId ?: UUID.randomUUID().toString(),
    )

    private fun upcomingUserQueue(timeline: List<Song>, currentIndex: Int): List<Song> =
        if (currentIndex in timeline.indices) {
            timeline.subList(currentIndex + 1, timeline.size)
                .filter { it.queueTier == QueueTier.USER_QUEUE }
        } else {
            emptyList()
        }

    private fun Song.from(source: QueueSource): Song = copy(
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )

    fun buildContextQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        newContextSongs: List<Song>,
        selectedIndex: Int,
        contextSource: QueueSource,
    ): ContextQueueResult {
        if (newContextSongs.isEmpty()) return ContextQueueResult(emptyList(), 0)

        val upcomingUserQueue = upcomingUserQueue(currentTimeline, currentIndex)
        val contextEntries = newContextSongs.map { it.from(contextSource).asQueueEntry(QueueTier.CONTEXT) }

        val safeIndex = selectedIndex.coerceIn(contextEntries.indices)
        val precedingContext = contextEntries.subList(0, safeIndex)
        val selected = contextEntries[safeIndex]
        val followingContext = contextEntries.subList(safeIndex + 1, contextEntries.size)

        return ContextQueueResult(
            timeline = precedingContext + listOf(selected) + upcomingUserQueue + followingContext,
            startIndex = precedingContext.size,
        )
    }

    fun buildOneOffQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        tappedSong: Song,
        source: QueueSource,
    ): List<Song> =
        listOf(tappedSong.from(source).asQueueEntry(QueueTier.CONTEXT)) +
            upcomingUserQueue(currentTimeline, currentIndex)

    fun buildPartyPlaybackQueue(
        tappedSong: Song,
        source: QueueSource,
        upcomingPartySongs: List<Song>,
    ): List<Song> =
        listOf(tappedSong.from(source).asQueueEntry(QueueTier.CONTEXT)) +
            upcomingPartySongs
                .filterNot { it.fromAutoplay }
                .map { it.asQueueEntry(QueueTier.USER_QUEUE) }

    fun findUserQueueInsertionIndex(
        timeline: List<Song>,
        currentIndex: Int,
        isNext: Boolean,
    ): Int {
        if (timeline.isEmpty()) return 0
        val start = (currentIndex + 1).coerceIn(0, timeline.size)
        if (isNext) return start
        for (i in start until timeline.size) {
            if (timeline[i].queueTier != QueueTier.USER_QUEUE) return i
        }
        return timeline.size
    }

    fun computeJumpOrder(
        tiers: List<QueueTier>,
        currentIndex: Int,
        targetIndex: Int,
    ): JumpOrderPlan? {
        if (currentIndex !in tiers.indices || targetIndex !in tiers.indices) return null
        if (targetIndex <= currentIndex) {
            return JumpOrderPlan(
                isPureSeek = true,
                newOrderIndices = tiers.indices.toList(),
                newTargetIndex = targetIndex,
            )
        }

        val targetTier = tiers[targetIndex]
        return when (targetTier) {
            QueueTier.CONTEXT -> {
                val crossedUserQueue = (currentIndex + 1 until targetIndex).filter { tiers[it] == QueueTier.USER_QUEUE }
                if (crossedUserQueue.isEmpty()) {
                    JumpOrderPlan(
                        isPureSeek = true,
                        newOrderIndices = tiers.indices.toList(),
                        newTargetIndex = targetIndex,
                    )
                } else {
                    val precedingHistory = (0..currentIndex).toList()
                    val bypassedContext = (currentIndex + 1 until targetIndex).filter { tiers[it] == QueueTier.CONTEXT }
                    val target = listOf(targetIndex)
                    val followingUserQueue = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                    val allUpcomingUserQueue = crossedUserQueue + followingUserQueue
                    val followingContext = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.CONTEXT }
                    val followingAutoplay = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                    val order = precedingHistory + bypassedContext + target + allUpcomingUserQueue + followingContext + followingAutoplay
                    val newTarget = precedingHistory.size + bypassedContext.size
                    JumpOrderPlan(isPureSeek = false, newOrderIndices = order, newTargetIndex = newTarget)
                }
            }
            QueueTier.USER_QUEUE -> {
                val history = (0..currentIndex).toList()
                val target = listOf(targetIndex)
                val subsequentUserQueue = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                val upcomingContext = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.CONTEXT }
                val upcomingAutoplay = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                val order = history + target + subsequentUserQueue + upcomingContext + upcomingAutoplay
                JumpOrderPlan(isPureSeek = false, newOrderIndices = order, newTargetIndex = history.size)
            }
            QueueTier.AUTOPLAY -> {
                val history = (0..currentIndex).toList()
                val target = listOf(targetIndex)
                val preservedUserQueue = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                val subsequentAutoplay = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                val order = history + target + preservedUserQueue + subsequentAutoplay
                JumpOrderPlan(isPureSeek = false, newOrderIndices = order, newTargetIndex = history.size, promoteTargetToContext = true)
            }
        }
    }

    fun buildJumpQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        targetIndex: Int,
    ): List<Song>? {
        val tiers = currentTimeline.map { it.queueTier }
        val plan = computeJumpOrder(tiers, currentIndex, targetIndex) ?: return null
        if (plan.isPureSeek && targetIndex <= currentIndex) return null

        val targetSong = currentTimeline[targetIndex]
        return plan.newOrderIndices.drop(plan.newTargetIndex).map { idx ->
            val song = currentTimeline[idx]
            if (idx == targetIndex && plan.promoteTargetToContext) {
                val sourceTitle = targetSong.playbackSource?.ifBlank { targetSong.title } ?: targetSong.title
                song.copy(
                    playbackSource = sourceTitle,
                    playbackSourceType = targetSong.playbackSourceType ?: PlaybackSourceType.QUEUE,
                ).asQueueEntry(QueueTier.CONTEXT)
            } else {
                song
            }
        }
    }

    fun upcomingUserQueueIndices(tiers: List<QueueTier>, currentIndex: Int): List<Int> =
        ((currentIndex + 1).coerceAtLeast(0) until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }

    fun playedUserQueueIndices(tiers: List<QueueTier>, currentIndex: Int): List<Int> {
        if (currentIndex !in tiers.indices || currentIndex == 0) return emptyList()
        if (tiers[currentIndex] != QueueTier.CONTEXT) return emptyList()
        return (0 until currentIndex).filter { tiers[it] == QueueTier.USER_QUEUE }
    }

    fun shuffledStartingOrder(songs: List<Song>, startIndex: Int): List<Song> {
        if (songs.isEmpty()) return songs
        val at = startIndex.coerceIn(songs.indices)
        val rest = songs.filterIndexed { i, _ -> i != at }
        return listOf(songs[at]) +
            rest.filter { it.queueTier == QueueTier.USER_QUEUE } +
            rest.filter { it.queueTier == QueueTier.CONTEXT }.shuffled() +
            rest.filter { it.queueTier == QueueTier.AUTOPLAY }.shuffled()
    }

    fun shuffledUpcomingOrder(upcoming: List<QueueTier>): List<Int> {
        val user = upcoming.indices.filter { upcoming[it] == QueueTier.USER_QUEUE }
        val context = upcoming.indices.filter { upcoming[it] == QueueTier.CONTEXT }
        val autoplay = upcoming.indices.filter { upcoming[it] == QueueTier.AUTOPLAY }
        return user + avoidIdentityShuffle(context, context.shuffled()) +
            avoidIdentityShuffle(autoplay, autoplay.shuffled())
    }

    fun sectionedOrder(restored: List<Int>, upcoming: List<QueueTier>): List<Int> =
        restored.filter { upcoming[it] == QueueTier.USER_QUEUE } +
            restored.filter { upcoming[it] == QueueTier.CONTEXT } +
            restored.filter { upcoming[it] == QueueTier.AUTOPLAY }

    fun avoidIdentityShuffle(original: List<Int>, shuffled: List<Int>): List<Int> {
        if (original.size <= 1 || shuffled != original) return shuffled
        return shuffled.drop(1) + shuffled.first()
    }
}
