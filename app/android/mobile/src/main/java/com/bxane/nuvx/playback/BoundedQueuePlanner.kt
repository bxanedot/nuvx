package com.bxane.nuvx.playback

import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song

data class BoundedContextPlan(
    val canonicalContext: List<Song>,
    val currentCanonicalIndex: Int,
    val history: List<Song>,
    val current: Song,
    val userQueue: List<Song>,
    val contextUpcoming: List<QueueWindowEntry>,
    val autoplay: List<Song>,
    val contextTrulyExhausted: Boolean,
    val continuationPending: Boolean,
    val continuationState: ContinuationState,
    val repeatAll: Boolean,
) {
    fun materializedSongs(): List<Song> = buildList(
        history.size + 1 + userQueue.size + contextUpcoming.size + autoplay.size,
    ) {
        addAll(history)
        add(current)
        addAll(userQueue)
        contextUpcoming.forEach { entry ->
            canonicalContext.getOrNull(entry.canonicalIndex)?.let { song ->
                add(if (song.queueTier == QueueTier.CONTEXT) song else song.copy(queueTier = QueueTier.CONTEXT))
            }
        }
        addAll(autoplay)
    }
}

object BoundedQueuePlanner {
    const val MAX_PHYSICAL_CONTEXT = MAX_QUEUE_HISTORY + 1 + WINDOW_AHEAD

    fun buildInitialPlan(
        contextSnapshot: ContextSnapshot,
        selectedIndex: Int,
        queueWindow: QueueWindow,
        preservedUserQueue: List<Song> = emptyList(),
        existingAutoplay: List<Song> = emptyList(),
        repeatAll: Boolean = false,
    ): BoundedContextPlan {
        val canonical = contextSnapshot.songs
        require(canonical.isNotEmpty()) { "contextSnapshot must contain songs" }
        require(selectedIndex in canonical.indices) {
            "selectedIndex $selectedIndex is outside canonicalContext"
        }
        require(queueWindow.canonicalSize == canonical.size) {
            "QueueWindow and contextSnapshot must have the same size"
        }
        require(queueWindow.currentCanonicalIndex == selectedIndex) {
            "QueueWindow must be started at selectedIndex"
        }

        val state = contextSnapshot.continuationState
        val upcoming = queueWindow.upcoming(
            WINDOW_AHEAD,
            repeatAll = false,
        )
        val history = historyBefore(canonical, queueWindow)
        return BoundedContextPlan(
            canonicalContext = canonical.toList(),
            currentCanonicalIndex = selectedIndex,
            history = history,
            current = canonical[selectedIndex].asContext(),
            userQueue = preservedUserQueue.map { it.asUserQueue() },
            contextUpcoming = upcoming,
            autoplay = existingAutoplay.map { it.asAutoplay() },
            contextTrulyExhausted = upcoming.isEmpty() && state.isTerminal() && !repeatAll,
            continuationPending = state.isPending(),
            continuationState = state,
            repeatAll = repeatAll,
        )
    }

    fun topUpEntries(
        window: QueueWindow,
        materialized: List<QueueWindowEntry>,
        repeatAll: Boolean,
    ): List<QueueWindowEntry> {
        if (materialized.size >= LOW_WATER) return emptyList()
        val desired = physicalUpcoming(window, repeatAll)
        if (materialized.size > desired.size) return emptyList()
        if (materialized.indices.any { !sameEntry(materialized[it], desired[it]) }) {
            return emptyList()
        }
        return desired.drop(materialized.size)
    }

    fun autoplayEligible(
        window: QueueWindow,
        continuationState: ContinuationState,
        repeatAll: Boolean,
    ): Boolean = !repeatAll &&
        window.upcoming(1, repeatAll = false).isEmpty() &&
        continuationState.isTerminal()

    fun canRepeat(continuationState: ContinuationState): Boolean = continuationState.isTerminal()

    fun shouldMaterializeNextCycle(window: QueueWindow, repeatAll: Boolean): Boolean =
        repeatAll && window.upcoming(1, repeatAll = false).isEmpty()

    fun physicalUpcoming(
        window: QueueWindow,
        repeatAll: Boolean,
        count: Int = WINDOW_AHEAD,
    ): List<QueueWindowEntry> {
        if (count <= 0) return emptyList()
        val active = window.upcoming(count, repeatAll = false)
        if (active.size >= count || !shouldMaterializeNextCycle(window, repeatAll)) {
            return active
        }
        val nextCycle = window.cycleId.takeIf { it < Long.MAX_VALUE }?.plus(1L)
            ?: return active
        val next = window.upcoming(count - active.size, repeatAll = true)
            .filter { it.cycleId == nextCycle }
        return active + next
    }

    private fun historyBefore(canonical: List<Song>, window: QueueWindow): List<Song> {
        val cursor = window.cursor
        val first = (cursor - MAX_QUEUE_HISTORY).coerceAtLeast(0)
        return window.order.subList(first, cursor).map { canonical[it].asContext() }
    }

    private fun sameEntry(left: QueueWindowEntry, right: QueueWindowEntry): Boolean =
        left.canonicalIndex == right.canonicalIndex && left.cycleId == right.cycleId

    private fun Song.asContext(): Song =
        if (queueTier == QueueTier.CONTEXT) this else copy(queueTier = QueueTier.CONTEXT)

    private fun Song.asUserQueue(): Song =
        if (queueTier == QueueTier.USER_QUEUE) this else copy(queueTier = QueueTier.USER_QUEUE)

    private fun Song.asAutoplay(): Song =
        if (queueTier == QueueTier.AUTOPLAY) this else copy(queueTier = QueueTier.AUTOPLAY)

    private fun ContinuationState.isPending(): Boolean =
        this == ContinuationState.AVAILABLE || this == ContinuationState.IN_FLIGHT

    private fun ContinuationState.isTerminal(): Boolean =
        this == ContinuationState.EXHAUSTED || this == ContinuationState.BLOCKED
}
