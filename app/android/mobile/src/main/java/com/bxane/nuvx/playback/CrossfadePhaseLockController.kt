package com.bxane.nuvx.playback

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.playback.smart.TrackAnalysis
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class CrossfadePhaseLockController(
    private val incomingBaseSpeed: () -> Float,
    private val incomingPlaybackRate: () -> Double,
    private val mediaRatio: () -> Double,
    private val outgoingAnalysis: () -> TrackAnalysis,
    private val incomingAnalysis: () -> TrackAnalysis,
    private val positionMs: (ExoPlayer) -> Double,
) {
    private var nudgeUntil = 0L
    private var lockQuietUntil = 0L
    private val lockErrors = DoubleArray(LOCK_SAMPLES)
    private var lockCount = 0
    private val beatScratch = DoubleArray(2)

    val beatOffsetSeconds get() = beatScratch[0]
    val beatIntervalSeconds get() = beatScratch[1]

    fun startFade(fadeStartedAt: Long, resetClocks: () -> Unit) {
        nudgeUntil = 0L
        lockCount = 0
        resetClocks()
        lockQuietUntil = fadeStartedAt + LOCK_START_MS
    }

    fun cancelNudge() {
        nudgeUntil = 0L
    }

    fun beatAt(analysis: TrackAnalysis, positionSec: Double): Boolean {
        val fromTempo = if (analysis.bpm > 0.0) 60.0 / analysis.bpm else 0.0
        val interval = analysis.beatInterval
        val nominal = when {
            interval > 0.0 && (fromTempo <= 0.0 || abs(interval / fromTempo - 1.0) < 0.25) -> interval
            fromTempo > 0.0 -> fromTempo
            else -> return false
        }
        val downbeats = analysis.downbeats
        val next = downbeats.binarySearch(positionSec).let { if (it >= 0) it + 1 else -it - 1 }
        val origin: Double
        var beat = nominal
        if (next in 1 until downbeats.size) {
            origin = downbeats[next - 1]
            val bar = downbeats[next] - origin
            val beatsInBar = (bar / nominal).roundToInt()
            if (beatsInBar in 1..8) beat = bar / beatsInBar
        } else {
            origin = if (next >= 1) downbeats[next - 1] else analysis.firstBeat
        }
        if (beat <= 0.0 || !beat.isFinite()) return false
        beatScratch[0] = ((positionSec - origin) % beat + beat) % beat
        beatScratch[1] = beat
        return true
    }

    fun hold(out: ExoPlayer, into: ExoPlayer, progress: Float) {
        val now = SystemClock.elapsedRealtime()
        if (nudgeUntil != 0L) {
            if (now < nudgeUntil && into.isPlaying) return
            nudgeUntil = 0L
            into.setPlaybackSpeed(incomingBaseSpeed())
            lockQuietUntil = now + LOCK_SETTLE_MS
            lockCount = 0
            return
        }
        if (progress >= LOCK_UNTIL || now < lockQuietUntil) return
        if (!out.isPlaying || !into.isPlaying) {
            lockCount = 0
            return
        }
        val error = phaseError(out, into)
        if (error.isNaN()) return
        lockErrors[lockCount++] = error
        if (lockCount < LOCK_SAMPLES) return
        lockCount = 0
        lockErrors.sort()
        val median = lockErrors[LOCK_SAMPLES / 2]
        if (abs(median) < LOCK_DEADBAND_SECONDS) {
            lockQuietUntil = now + LOCK_IN_PHASE_MS
            return
        }
        val base = incomingBaseSpeed()
        val rate = incomingPlaybackRate()
        val factor = if (median > 0) {
            1f - NUDGE
        } else {
            minOf(1f + NUDGE, (MAX_TOTAL_SPEED_UP / rate).toFloat())
        }
        if (factor <= 1f && median < 0) return
        into.setPlaybackSpeed(base * factor)
        nudgeUntil = now + (abs(median) / (base * abs(factor - 1f)) * 1000).roundToLong().coerceIn(1L, MAX_NUDGE_MS)
    }

    private fun phaseError(out: ExoPlayer, into: ExoPlayer): Double {
        if (!beatAt(outgoingAnalysis(), positionMs(out) / 1000.0)) return Double.NaN
        val outSince = beatScratch[0]
        val outBeat = beatScratch[1]
        if (!beatAt(incomingAnalysis(), positionMs(into) / 1000.0)) return Double.NaN
        val rate = mediaRatio()
        val period = minOf(outBeat, beatScratch[1] / rate)
        var diff = (beatScratch[0] / rate - outSince) % period
        if (diff > period / 2) diff -= period else if (diff < -period / 2) diff += period
        if (abs(diff) > period * LOCK_MAX_BEATS) return Double.NaN
        return diff * rate
    }

    private companion object {
        const val LOCK_SAMPLES = 5
        const val LOCK_DEADBAND_SECONDS = 0.008
        const val LOCK_SETTLE_MS = 400L
        const val LOCK_IN_PHASE_MS = 500L
        const val NUDGE = 0.03f
        const val MAX_NUDGE_MS = 1_500L
        const val LOCK_UNTIL = 0.9f
        const val LOCK_MAX_BEATS = 0.3
        const val MAX_TOTAL_SPEED_UP = 1.12
        const val LOCK_START_MS = 300L
    }
}
