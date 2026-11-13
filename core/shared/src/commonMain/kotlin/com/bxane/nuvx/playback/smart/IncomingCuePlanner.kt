

package com.bxane.nuvx.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal object IncomingCuePlanner {
    fun nearestTimedValue(
        values: List<Double>,
        target: Double,
        tolerance: Double = Double.POSITIVE_INFINITY,
        minimum: Double = 0.0,
    ): Double? = values
        .filter { it.isFinite() && it >= minimum && abs(it - target) <= tolerance }
        .minByOrNull { abs(it - target) }

    fun timedValueNearOrBefore(
        values: List<Double>,
        target: Double,
        tolerance: Double = Double.POSITIVE_INFINITY,
        minimum: Double = 0.0,
    ): Double? = values
        .filter { it.isFinite() && it >= minimum && it <= target && target - it <= tolerance }
        .maxOrNull()

    fun alignedTransitionStart(
        analysis: TrackAnalysis,
        target: Double,
        end: Double,
        preferEarlier: Boolean,
        minimum: Double,
    ): Double {
        val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
            ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
        val phraseTolerance = max(1.0, interval * 4)
        val downbeatTolerance = max(0.75, interval * 2)
        val phrase = if (preferEarlier) {
            timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
        } else {
            nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum)
        }
        val downbeat = if (preferEarlier) {
            timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
        } else {
            nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum)
        }
        return clamp(phrase ?: downbeat ?: target, minimum, end)
    }

    fun latestIncomingCue(length: Double): Double =
        if (length.isFinite() && length > 0) {
            length * MAX_INCOMING_SKIP_FRACTION
        } else {
            Double.POSITIVE_INFINITY
        }

    fun incomingCuePoint(analysis: TrackAnalysis, latest: Double): Double {
        rankMixInCandidates(analysis).firstOrNull { it.time <= latest }?.let { return it.time }

        val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
            ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
        val downbeats = analysis.downbeats
        fun within(time: Double): Double =
            if (time <= latest) time else nearestAtOrBefore(downbeats, latest) ?: latest

        val analyzedMixIn = analysis.mixInTime
        if (analyzedMixIn.isFinite() && analyzedMixIn > 0) {
            return within(nearestTimedValue(downbeats, analyzedMixIn, max(0.5, interval * 2)) ?: analyzedMixIn)
        }

        val pickup = max(
            0.0,
            analysis.introEndTime.orZero().takeIf { it != 0.0 }
                ?: (analysis.audibleStartTime ?: analysis.pickupTime).orZero().takeIf { it != 0.0 }
                ?: analysis.firstBeat.orZero(),
        )
        val duration = analysis.duration.orZero().takeIf { it != 0.0 } ?: 300.0
        if (pickup > 0 && pickup < duration - 10) {
            downbeats.firstOrNull { it >= pickup }?.let { return within(it) }
        }
        val phrases = analysis.phraseBoundaries
        if (phrases.size > 1 && phrases[1] > 4) return within(phrases[1])
        if (downbeats.size >= 8) return within(downbeats[min(8, downbeats.size - 1)].orZero())
        return within(pickup)
    }

    fun capIncomingSkip(plan: TransitionPlan, nextAnalysis: TrackAnalysis, nextLength: Double): TransitionPlan {
        val latest = latestIncomingCue(nextLength)
        if (plan.incomingCueTime <= latest) return plan
        val beat = nextAnalysis.beatInterval.orZero().takeIf { it > 0 }
            ?: if (nextAnalysis.bpm.orZero() > 0) 60 / nextAnalysis.bpm else 0.0
        val over = plan.incomingCueTime - latest
        val shift = if (beat > 0) ceil(over / (4 * beat)) * 4 * beat else over
        val cue = max(0.0, plan.incomingCueTime - shift)
        val moved = plan.incomingCueTime - cue
        return plan.copy(
            incomingCueTime = cue,
            incomingHandoffTime = max(cue, plan.incomingHandoffTime - moved),
        )
    }

    fun incomingStartPoint(analysis: TrackAnalysis): Double =
        listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat)
            .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0

    fun incomingMixInPoint(analysis: TrackAnalysis, latest: Double): Double? {
        val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
            ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
        val tolerance = max(0.5, beatSeconds * 2)
        val target = listOfNotNull(
            rankMixInCandidates(analysis).firstOrNull { it.time <= latest }?.time,
            analysis.mixInTime,
        )
            .firstOrNull { it.isFinite() && it > 0 && it <= latest }
            ?: return null
        return nearestValue(analysis.downbeats, target, tolerance)?.takeIf { it <= latest } ?: target
    }

    fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)

    fun nearestAtOrBefore(values: List<Double>, target: Double): Double? =
        values.filter { it.isFinite() && it >= 0 && it <= target }.maxOrNull()
}
