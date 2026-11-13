package com.bxane.nuvx.playback.smart

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

private const val AUTO_TRANSITION_MAX_BEATS = 16.0
private const val AUTO_MIN_SECONDS = 4.0
private const val AUTO_FAST_TRACK_MIN_SECONDS = 6.0
private const val AUTO_TRANSITION_MAX_SECONDS = 12.0
private const val AUTO_FALLBACK_SECONDS = 8.0

private const val KICK_PRESENT = 0.5

private fun fallbackVariantFor(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    exitAt: Double,
    beatSeconds: Double,
): FilterTransitionVariant {
    val window = ENERGY_WINDOW_BEATS * (beatSeconds.takeIf { it > 0 } ?: 0.5)
    val entry = IncomingCuePlanner.incomingStartPoint(nextAnalysis)
    val outReference = BassHandoffPlanner.lowEnergyReference(analysis.lowEnergyCurve)
        ?: return FilterTransitionVariant.SWEEP
    val inReference = BassHandoffPlanner.lowEnergyReference(nextAnalysis.lowEnergyCurve)
        ?: return FilterTransitionVariant.SWEEP
    val out = BassHandoffPlanner.averageEnergy(analysis.lowEnergyCurve, exitAt - window, exitAt)
        ?: return FilterTransitionVariant.SWEEP
    val into = BassHandoffPlanner.averageEnergy(nextAnalysis.lowEnergyCurve, entry, entry + window)
        ?: return FilterTransitionVariant.SWEEP
    return if (out / outReference >= KICK_PRESENT && into / inReference >= KICK_PRESENT) {
        FilterTransitionVariant.BASS_HANDOFF
    } else {
        FilterTransitionVariant.SWEEP
    }
}

private const val MAX_ADVANCED_BEATS = 32
private const val CALM_MAX_SECONDS = 16.0

private const val ENERGY_WINDOW_BEATS = 8

private const val CALM_ENERGY = 0.7


private const val VOCAL_SAFE_FALLBACK_THRESHOLD = 0.18

private const val VOCAL_MASK_MISSING_PROTECTION = 0.55


private fun energyScale(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    traits: TransitionPairCompatibility.PairTraits,
    exitAt: Double,
    beatSeconds: Double,
): Double {
    if (beatSeconds <= 0 || traits.outgoingMeanEnergy <= 0 || traits.incomingMeanEnergy <= 0) return 1.0
    val window = ENERGY_WINDOW_BEATS * beatSeconds
    val entry = IncomingCuePlanner.incomingStartPoint(nextAnalysis)
    val out = BassHandoffPlanner.averageEnergy(analysis.energyCurve, exitAt - window, exitAt) ?: return 1.0
    val into = BassHandoffPlanner.averageEnergy(nextAnalysis.energyCurve, entry, entry + window) ?: return 1.0
    val outLevel = out / traits.outgoingMeanEnergy
    val inLevel = into / traits.incomingMeanEnergy
    return if (outLevel < CALM_ENERGY && inLevel < CALM_ENERGY) 2.0 else 1.0
}

private fun wholeBars(seconds: Double, beatSeconds: Double, maximum: Double): Double {
    if (beatSeconds <= 0) return seconds
    val bar = 4 * beatSeconds
    var bars = round(seconds / bar)
    if (bars * bar > maximum + 1e-6) bars = floor(maximum / bar + 1e-6)
    return if (bars >= 1) bars * bar else seconds
}

private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

private fun adaptiveOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    traits: TransitionPairCompatibility.PairTraits? = null,
    energy: Double = 1.0,
    filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
): Overlap {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    if (currentBpm <= 0 || nextBpm <= 0) {
        return Overlap(AUTO_FALLBACK_SECONDS, 0, 1.0)
    }

    val ratio = TransitionPairCompatibility.normalizedTempoRatio(currentBpm, nextBpm)
    val transitionBeats = if (traits == null) {
        val distance = TransitionPairCompatibility.keyDistance(
            TransitionPairCompatibility.trustedKey(analysis),
            TransitionPairCompatibility.trustedKey(nextAnalysis),
        )
        val vocalConflict = analysis.vocalProbability >= TransitionPairCompatibility.VOCAL_CONFLICT_PROBABILITY &&
            nextAnalysis.vocalProbability >= TransitionPairCompatibility.VOCAL_CONFLICT_PROBABILITY
        if (!vocalConflict && (abs(1 - ratio) > 0.07 || (distance != null && distance > 4))) 16 else 8
    } else {
        val base = if (traits.tempoFar) {
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> 12
                FilterTransitionVariant.BASS_HANDOFF,
                FilterTransitionVariant.ECHO_RIDE -> 8
            }
        } else if (traits.keyClash || traits.vocalConflict) {
            8
        } else {
            16
        }
        (base * energy).roundToInt().coerceIn(MIN_FADE_BEATS, MAX_ADVANCED_BEATS)
    }
    val beatSeconds = 60 / currentBpm
    val minimumOverlap = if (currentBpm >= 140) AUTO_FAST_TRACK_MIN_SECONDS else AUTO_MIN_SECONDS
    val maximumOverlap = if (traits != null && energy > 1) CALM_MAX_SECONDS else AUTO_TRANSITION_MAX_SECONDS
    val overlap = clamp(transitionBeats * beatSeconds, minimumOverlap, maximumOverlap)

    return Overlap(
        overlap = if (traits != null) wholeBars(overlap, beatSeconds, maximumOverlap) else overlap,
        transitionBeats = transitionBeats,
        incomingPlaybackRate = when {
            traits != null -> if (traits.tempoFar) 1.0 else roundRate(1 / ratio)
            1 / ratio in MIN_INCOMING_RATE..MAX_INCOMING_RATE -> roundRate(1 / ratio)
            else -> 1.0
        },
    )
}

internal fun mixPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    mixAnchor: Double,
    mixOutType: String,
    nextLength: Double,
    traits: TransitionPairCompatibility.PairTraits?,
): TransitionPlan {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    val gridBeatSeconds = if (traits?.outgoingGrid == true) 60 / currentBpm else 0.0
    val filterVariant = if (traits != null) {
        fallbackVariantFor(analysis, nextAnalysis, mixAnchor, if (currentBpm > 0) 60 / currentBpm else 0.0)
    } else {
        FilterTransitionVariant.SWEEP
    }
    val energy = if (traits != null) {
        energyScale(analysis, nextAnalysis, traits, mixAnchor, gridBeatSeconds)
    } else {
        1.0
    }
    val (overlap, transitionBeats, incomingPlaybackRate) = adaptiveOverlap(
        analysis, nextAnalysis, traits, energy, filterVariant,
    )
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    val sameBeatBlend = (traits == null || incomingPlaybackRate >= 1.0) && (
        currentBpm > 0 && nextBpm > 0 &&
            abs(1 - TransitionPairCompatibility.normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
            (analysis.beatConfidence.orZero() >= 0.2 || nextAnalysis.beatConfidence.orZero() >= 0.2) ||
            traits?.phaseLockable == true
        )
    val outgoingArrangementOverlap =
        if (sameBeatBlend && mixOutType == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * 60 / currentBpm, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val arrangementEnd = max(0.0, mixAnchor - outgoingArrangementOverlap)
    val mixEnd = if (gridBeatSeconds > 0) {
        onDownbeatBefore(analysis, arrangementEnd, 4 * gridBeatSeconds)
    } else {
        arrangementEnd
    }
    val maxBeats = if (traits != null) MAX_ADVANCED_BEATS.toDouble() else AUTO_TRANSITION_MAX_BEATS
    val fallbackMaxSeconds = when (filterVariant) {
        FilterTransitionVariant.SWEEP -> 8.0
        FilterTransitionVariant.BASS_HANDOFF -> 7.0
        FilterTransitionVariant.ECHO_RIDE -> 7.0
    }
    val maxSeconds = when {
        traits != null && !sameBeatBlend -> fallbackMaxSeconds
        traits != null && energy > 1 -> CALM_MAX_SECONDS
        else -> AUTO_TRANSITION_MAX_SECONDS
    }
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (maxBeats * 60) / handoffBpm else maxSeconds,
        maxSeconds,
        mixEnd * 0.4,
        if (nextLength > 0) nextLength * 0.4 else maxSeconds,
    )
    val handoffBeats = if (sameBeatBlend) 8 else 4
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    val incomingDropTime = IncomingCuePlanner.incomingCuePoint(
        nextAnalysis,
        IncomingCuePlanner.latestIncomingCue(nextLength),
    )
    val alignedIncomingBpm = alignTempoOctave(currentBpm, nextBpm)
    val requestedIncomingHandoff =
        if (sameBeatBlend && alignedIncomingBpm > 0) {
            incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * 60 / alignedIncomingBpm
        } else {
            incomingDropTime
        }
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = IncomingCuePlanner.incomingStartPoint(nextAnalysis)

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val clamped = clamp(introDropTime, min(12.0, maximumOverlap), maximumOverlap)
        val totalOverlap = if (traits != null) wholeBars(clamped, beatSeconds, maximumOverlap) else clamped
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = IncomingCuePlanner.alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val clamped = clamp(overlap, min(handoffSeconds, maximumOverlap), maximumOverlap)
        val actualOverlap = if (traits != null) wholeBars(clamped, beatSeconds, maximumOverlap) else clamped
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = IncomingCuePlanner.alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = false,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime = rawIncomingCueTime
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    val measuredVocalOverlap = plannedVocalOverlap(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        incomingCueTime = finalIncomingCueTime,
        incomingPlaybackRate = incomingPlaybackRate,
    )
    val vocalOverlap = max(
        measuredVocalOverlap,
        if (
            traits?.vocalConflict == true &&
            (analysis.vocalActivityMask.isEmpty() || nextAnalysis.vocalActivityMask.isEmpty())
        ) VOCAL_MASK_MISSING_PROTECTION else 0.0,
    )
    val vocalSafeFallback = !sameBeatBlend && vocalOverlap >= VOCAL_SAFE_FALLBACK_THRESHOLD
    val echoSeconds = 0.0
    val resolvedFilterVariant = if (vocalSafeFallback) FilterTransitionVariant.SWEEP else filterVariant
    return TransitionPlan(
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = incomingRateFor(incomingPlaybackRate, traits != null),
        outgoingPlaybackRate = outgoingRateFor(incomingPlaybackRate, traits != null),
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = sameBeatBlend || hasBassContent,
        transitionStyle = if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,
        filterSweep = if (sameBeatBlend) 0.0 else FILTER_SWEEP,
        filterVariant = if (sameBeatBlend) FilterTransitionVariant.SWEEP else resolvedFilterVariant,
        vocalOverlap = vocalOverlap,
        beatSeconds = gridBeatSeconds,
        phaseLock = false,
        echoSeconds = echoSeconds,
        reason = "smart-duration",
    )
}
