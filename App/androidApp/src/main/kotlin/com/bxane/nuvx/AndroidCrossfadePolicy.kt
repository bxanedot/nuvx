package com.bxane.nuvx

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.RepeatMode

internal object AndroidCrossfadePolicy {
    fun nextTrackIndex(index: Int, count: Int, repeatMode: RepeatMode): Int? {
        if (count < 2 || index !in 0 until count || repeatMode == RepeatMode.ONE) return null
        if (index < count - 1) return index + 1
        return 0.takeIf { repeatMode == RepeatMode.ALL }
    }

    fun isEligible(outgoing: MediaKind, incoming: MediaKind, repeatMode: RepeatMode): Boolean =
        outgoing == MediaKind.SONG && incoming == MediaKind.SONG && repeatMode != RepeatMode.ONE

    fun progress(positionMs: Long, startPositionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else ((positionMs - startPositionMs).toFloat() / durationMs).coerceIn(0f, 1f)
}
