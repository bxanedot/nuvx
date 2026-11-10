package com.bxane.nuvx.playback

import androidx.media3.common.Player

internal const val MAX_QUEUE_HISTORY = 25

internal fun queueHistoryTrimCount(currentIndex: Int): Int =
    (currentIndex - MAX_QUEUE_HISTORY).coerceAtLeast(0)

internal fun Player.rotateExpiredHistory(count: Int) {
    if (count > 0) moveMediaItems(0, count, mediaItemCount - count)
}

internal fun skippedByQueueJump(currentIndex: Int, targetIndex: Int): IntRange? =
    if (targetIndex > currentIndex + 1) (currentIndex + 1) until targetIndex else null

internal fun <T> queueStartingAt(items: List<T>, startIndex: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    return items.drop(startIndex.coerceIn(items.indices))
}
