package com.bxane.nuvx.playback

import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class CrossfadeMixGeometry(
    val outStart: Double,
    val outEnd: Double,
    val inStart: Double,
    val inEnd: Double,
    val ratio: Double,
) {
    val outSpan get() = outEnd - outStart
    val context get() = outSpan * MIX_VIEW_CONTEXT
    val outFrom get() = outStart - context
    val outTo get() = outEnd + context
    val inFrom get() = inStart - context * ratio
    val inTo get() = inEnd + context * ratio
}

internal object CrossfadeGeometry {
    fun playback(
        fadeEndMs: Long,
        fadeMs: Long,
        advanced: Boolean,
        mediaRatio: Double,
        incomingCueTimeMs: Long,
    ): CrossfadeMixGeometry {
        val outEnd = fadeEndMs / 1000.0
        val outStart = outEnd - (fadeMs / 1000.0).coerceAtLeast(0.5)
        val ratio = if (advanced) mediaRatio else 1.0
        val inStart = incomingCueTimeMs / 1000.0
        return CrossfadeMixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
    }

    fun planned(plan: TransitionPlan): CrossfadeMixGeometry {
        val outStart = plan.transitionStart
        val outEnd = maxOf(plan.transitionEnd, outStart + 0.5)
        val inStart = plan.incomingCueTime
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
        return CrossfadeMixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
    }

    fun beatMarks(analysis: TrackAnalysis, outStart: Double, outEnd: Double, beatSeconds: Double): FloatArray {
        if (beatSeconds <= 0) return FloatArray(0)
        val span = outEnd - outStart
        val anchor = analysis.downbeats.lastOrNull { it <= outStart } ?: outStart
        val first = anchor + ceil((outStart - anchor) / beatSeconds) * beatSeconds
        return generateSequence(first) { it + beatSeconds }
            .takeWhile { it <= outEnd + 1e-6 }
            .map { ((it - outStart) / span).toFloat() }
            .toList().toFloatArray()
    }

    fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (kotlin.math.abs(beatMs - last.beatMs) > beatMs * TEMPO_TOLERANCE) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    const val MIX_VIEW_FADER_POINTS = 64
    private const val ANCHOR_TOLERANCE_NANOS = 25_000_000L
    private const val TEMPO_TOLERANCE = 0.005f
}
