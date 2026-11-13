package com.bxane.nuvx.playback.smart

import kotlin.math.min

private const val BEATS_PER_BAR = 4

private const val MAX_PHRASE_OVERLAP_SECONDS = 64.0

internal fun phraseBarPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
    phraseBars: Int,
    mixAnchor: Double,
    advanced: Boolean,
): TransitionPlan? {
    val outgoingBpm = analysis.bpm.orZero()
    if (outgoingBpm < MIN_BPM || outgoingBpm > MAX_BPM) return null
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 } ?: (60.0 / outgoingBpm)
    if (beatSeconds <= 0) return null

    val overlapBeats = phraseBars * BEATS_PER_BAR
    val requestedOverlap = overlapBeats * beatSeconds
    if (requestedOverlap <= 0 || requestedOverlap > MAX_PHRASE_OVERLAP_SECONDS) return null

    val contentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val anchor = min(mixAnchor, contentEnd).coerceAtMost(length)
    val gridTrusted = analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE
    val mixEnd = if (gridTrusted) onDownbeatBefore(analysis, anchor, BEATS_PER_BAR * beatSeconds) else anchor
    if (mixEnd <= 0) return null

    val latestStart = mixEnd - requestedOverlap
    if (latestStart < MIN_CLEARANCE_SECONDS) return null
    val transitionStart = if (gridTrusted) {
        IncomingCuePlanner.alignedTransitionStart(
            analysis,
            latestStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = MIN_CLEARANCE_SECONDS,
        )
    } else {
        latestStart
    }
    val transitionEnd = transitionStart + requestedOverlap
    if (transitionEnd > length + 0.05) return null
    val overlapSeconds = transitionEnd - transitionStart
    if (overlapSeconds <= 0) return null

    val policy = assessTransitionTier(analysis, nextAnalysis, advanced)
    val beatmatched = policy.tier == TransitionTier.BEATMATCHED
    val nextBpm = nextAnalysis.bpm.orZero()
    val alignedIncomingBpm = alignTempoOctave(outgoingBpm, nextBpm)
    val tempoFar = nextBpm > 0 && !stretchReachable(outgoingBpm, nextBpm)
    val mediaRatio =
        if (nextBpm > 0 && !tempoFar) {
            roundRate(1.0 / TransitionPairCompatibility.normalizedTempoRatio(outgoingBpm, nextBpm))
        } else {
            1.0
        }
    val incomingPlaybackRate = incomingRateFor(mediaRatio, advanced)
    val outgoingPlaybackRate = outgoingRateFor(mediaRatio, advanced)
    val incomingBeatSeconds = if (alignedIncomingBpm > 0) 60.0 / alignedIncomingBpm else beatSeconds

    val audibleStart = IncomingCuePlanner.incomingStartPoint(nextAnalysis)
    val incomingDropTime = IncomingCuePlanner.incomingCuePoint(
        nextAnalysis,
        IncomingCuePlanner.latestIncomingCue(nextLength),
    )
    val finalIncomingCueTime = audibleStart
    val incomingHandoffTime =
        if (nextLength > 0) {
            min(incomingDropTime, nextLength - MIN_INCOMING_CLEARANCE_SECONDS).coerceAtLeast(finalIncomingCueTime)
        } else {
            incomingDropTime
        }
    val incomingResume = finalIncomingCueTime + overlapSeconds * incomingPlaybackRate
    if (nextLength > 0 && incomingResume + MIN_INCOMING_CLEARANCE_SECONDS > nextLength) return null

    val vocalOverlap = plannedVocalOverlap(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        incomingCueTime = finalIncomingCueTime,
        incomingPlaybackRate = incomingPlaybackRate,
    )
    val bassSwapFraction = BassHandoffPlanner.bassSwapFractionFor(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        incomingCueTime = finalIncomingCueTime,
        outgoingBeatSeconds = beatSeconds,
        incomingBeatSeconds = incomingBeatSeconds,
        overlapSeconds = overlapSeconds,
        overlapBeats = overlapBeats,
    )
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()

    return TransitionPlan(
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        fadeSeconds = overlapSeconds,
        handoffStartSeconds = 0.0,
        handoffDuration = overlapSeconds,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = incomingPlaybackRate,
        outgoingPlaybackRate = outgoingPlaybackRate,
        pickupSeconds = audibleStart,
        transitionBeats = overlapBeats,
        bassSwap = beatmatched || hasBassContent,
        handoffFraction = HANDOFF_FRACTION,
        bedPosition = BED_POSITION,
        bassSwapFraction = bassSwapFraction,
        filterSweep = if (beatmatched) 0.0 else FILTER_SWEEP,
        filterVariant = FilterTransitionVariant.SWEEP,
        vocalOverlap = vocalOverlap,
        outgoingBpm = outgoingBpm,
        incomingBpm = alignedIncomingBpm,
        beatSeconds = if (gridTrusted) beatSeconds else 0.0,
        phaseLock = false,
        echoSeconds = 0.0,
        transitionStyle = if (beatmatched) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,
        reason = "smart-phrase-bars",
    )
}
