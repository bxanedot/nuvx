package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.playback.PlaybackPosition
import com.bxane.nuvx.ui.rememberIsForeground
import kotlin.math.abs

internal fun adjustedLyricsPosition(positionMs: Long, offsetMs: Int): Long =
    (positionMs - offsetMs.toLong()).coerceAtLeast(0L)

internal fun adjustedLyricsSeekTarget(lineTimeMs: Long, offsetMs: Int): Long =
    (lineTimeMs + offsetMs.toLong()).coerceAtLeast(0L)

@Stable
internal class LyricPlayhead(
    private val position: PlaybackPosition,
    private val offsetMs: State<Int>,
) {
    val positionMs: Long get() = adjustedLyricsPosition(position.positionMs, offsetMs.value)

    val sampledAtNanos: Long get() = position.sampledAtNanos

    val discontinuity: Long
        get() = (position.seeks.toLong() shl 32) or (offsetMs.value.toLong() and 0xFFFF_FFFFL)
}

@Composable
internal fun rememberLyricPlayhead(position: PlaybackPosition): LyricPlayhead {
    val offset = PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    return remember(position, offset) { LyricPlayhead(position, offset) }
}

@Composable
internal fun rememberLivePlaybackPosition(position: PlaybackPosition, isPlaying: Boolean, durationMs: Long): Long {
    val live = remember(position) { mutableLongStateOf(position.positionMs) }
    val foreground = rememberIsForeground()
    LaunchedEffect(position, isPlaying, position.advancing, durationMs, foreground) {
        if (!foreground) return@LaunchedEffect
        if (isPlaying && position.advancing) {
            while (true) withFrameNanos { now -> live.longValue = position.livePosition(true, now, durationMs) }
        } else {
            snapshotFlow { position.positionMs }.collect { live.longValue = it }
        }
    }
    return live.longValue
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

@Composable
internal fun rememberLyricClock(
    trackKey: Any,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation { playhead.positionMs })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    if (!isPlaying) {
        LaunchedEffect(engine, playhead) {
            snapshotFlow { playhead.positionMs }.collect { positionMs ->
                engine.hold(positionMs, nanosToMs(System.nanoTime()))
                clock.longValue = positionMs
            }
        }
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(engine, playhead, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val sampledAt = playhead.sampledAtNanos
                clock.longValue = engine.frame(
                    nowMs = nanosToMs(now),
                    reportedMs = playhead.positionMs,
                    sampledAtMs = if (sampledAt > 0L) nanosToMs(sampledAt) else Double.NaN,
                    discontinuity = playhead.discontinuity,
                )
            }
        }
    }
    return clock
}

private const val SAME_CLOCK_NANOS = 250_000_000L
