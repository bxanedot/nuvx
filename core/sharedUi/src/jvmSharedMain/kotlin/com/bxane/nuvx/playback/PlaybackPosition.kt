package com.bxane.nuvx.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class PlaybackPosition {
    var positionMs by mutableLongStateOf(0L)
        private set

    var sampledAtNanos by mutableLongStateOf(0L)
        private set

    var seeks by mutableIntStateOf(0)

    var advancing by mutableStateOf(true)

    fun report(positionMs: Long, sampledAtNanos: Long = System.nanoTime()) {
        this.positionMs = positionMs
        this.sampledAtNanos = sampledAtNanos
    }

    fun livePosition(isPlaying: Boolean, nowNanos: Long, durationMs: Long): Long {
        val read = positionMs
        if (!isPlaying || !advancing || sampledAtNanos == 0L || nowNanos == 0L) return read
        val carried = ((nowNanos - sampledAtNanos) / 1_000_000L).coerceIn(0L, 1_500L)
        return (read + carried).coerceAtMost(durationMs.coerceAtLeast(read))
    }
}
