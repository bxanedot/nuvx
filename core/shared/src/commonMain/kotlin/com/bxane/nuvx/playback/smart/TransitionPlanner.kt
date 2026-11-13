// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
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

private const val MIX_WINDOW_SECONDS = 30.0

private const val MIN_SMART_DURATION_SECONDS = 45.0

internal const val MIN_INCOMING_CLEARANCE_SECONDS = 5.0

private val SPOKEN_TEXT = Regex(
    """\b(podcast|episode|audiobook|concert)\b""",
    RegexOption.IGNORE_CASE,
)

private val LIVE_TEXT = Regex(
    """[(\[]\s*(?:[^)\]]*\s)?live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b[^)\]]*)?\s*[)\]]""" +
        """|[-–—]\s*live(?:\s+(?:at|from|in|on|version|session|recording|performance|edit)\b.*)?\s*$""" +
        """|^\s*live\s*(?::|\s(?:at|from|in|on)\b)""",
    RegexOption.IGNORE_CASE,
)

private fun isSpokenOrLive(track: TransitionTrackInfo?): Boolean {
    if (track == null) return false
    if (SPOKEN_TEXT.containsMatchIn(itemText(track))) return true
    return LIVE_TEXT.containsMatchIn(track.title) || LIVE_TEXT.containsMatchIn(track.album)
}

internal fun isSpokenOrLiveForTest(title: String, artist: String = "", album: String = ""): Boolean =
    isSpokenOrLive(TransitionTrackInfo(id = "t", durationMs = 0L, title = title, artist = artist, album = album))

private fun blocked(reason: String, transitionStart: Double = 0.0, transitionEnd: Double = 0.0) =
    TransitionPlan(
        blocked = true,
        reason = reason,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
    )

private fun trackDurationSeconds(track: TransitionTrackInfo?): Double =
    if (track == null || track.durationMs <= 0) 0.0 else track.durationMs / 1000.0

private fun itemText(track: TransitionTrackInfo?): String =
    if (track == null) "" else listOf(track.title, track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" ")

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

private fun sameAlbum(left: TransitionTrackInfo?, right: TransitionTrackInfo?): Boolean {
    if (left == null || right == null) return false
    if (left.albumId.isNotBlank() && left.albumId == right.albumId) return true
    return left.album.isNotBlank() && left.album == right.album && left.artist == right.artist
}

const val MAX_INCOMING_SKIP_FRACTION = 0.25

fun latestIncomingCue(length: Double): Double = IncomingCuePlanner.latestIncomingCue(length)

fun incomingCuePoint(
    analysis: TrackAnalysis,
    latest: Double = Double.POSITIVE_INFINITY,
): Double = IncomingCuePlanner.incomingCuePoint(analysis, latest)

const val PHRASE_BARS_SHORT = 8
const val PHRASE_BARS_LONG = 16

internal const val MIN_FADE_BEATS = 4
internal const val MAX_FADE_BEATS = 16

internal const val MAX_OVERLAP_SECONDS = 16.0

 const val ARRANGEMENT_OVERLAP_BEATS = 8

const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5

const val FILTER_SWEEP = 1.0

internal const val MIN_CLEARANCE_SECONDS = 5.0

internal fun plannedVocalOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    transitionEnd: Double,
    incomingCueTime: Double,
    incomingPlaybackRate: Double,
): Double {
    val outgoingSpan = transitionEnd - transitionStart
    if (outgoingSpan <= 0.0 || !outgoingSpan.isFinite()) return 0.0
    val rate = incomingPlaybackRate.takeIf { it.isFinite() && it > 0 } ?: 1.0
    return simultaneousVocalFraction(
        outgoing = analysis,
        incoming = nextAnalysis,
        outStart = transitionStart,
        outEnd = transitionEnd,
        inStart = incomingCueTime,
        rate = rate,
    ) ?: 0.0
}


private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

@Suppress("UNUSED_PARAMETER")
internal fun incomingRateFor(mediaRatio: Double, advanced: Boolean): Double =
    roundRate(clamp(mediaRatio, MIN_INCOMING_RATE, MAX_INCOMING_RATE))

internal fun outgoingRateFor(mediaRatio: Double, advanced: Boolean): Double =
    1.0

internal fun roundRate(rate: Double): Double = (rate * 10000).roundToInt() / 10000.0

private const val MAX_ADVANCED_BEATS = 32
private const val CALM_MAX_SECONDS = 16.0

private const val ENERGY_WINDOW_BEATS = 8

private const val CALM_ENERGY = 0.7

private const val VOCAL_REROUTE_OVERLAP = 0.3

private const val VOCAL_SAFE_FALLBACK_THRESHOLD = 0.18

private const val VOCAL_MASK_MISSING_PROTECTION = 0.55

private const val VOCAL_REROUTE_GAIN = 0.5
private const val MAX_REROUTE_CANDIDATES = 3

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

internal fun onDownbeatBefore(analysis: TrackAnalysis, time: Double, tolerance: Double): Double =
    IncomingCuePlanner.timedValueNearOrBefore(analysis.downbeats, time, tolerance) ?: time

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

const val ECHO_REPEATS = 5

const val FILTER_ECHO_AT = 0.5

const val MAX_ECHO_SECONDS = 1.0

fun echoTailSeconds(echoSeconds: Double): Double = echoSeconds * ECHO_REPEATS

private fun standardTransition(
    length: Double,
    playbackTime: Double,
    fadeSeconds: Double,
    minFadeSeconds: Double,
    reason: String = "standard",
): TransitionPlan {
    val fade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    val transitionStart = max(0.0, length - fade)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = length,
        fadeSeconds = fade,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        reason = if (started) reason else "before-$reason-window",
    )
}

private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}

fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    advanced: Boolean = false,
    phraseBars: Int = 0,
    mixWindow: Boolean = false,
): TransitionPlan {
    val plan = planTransitionInner(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        currentTrack = currentTrack,
        nextTrack = nextTrack,
        currentTime = currentTime,
        duration = duration,
        fadeSeconds = fadeSeconds,
        minFadeSeconds = minFadeSeconds,
        mode = mode,
        albumSequential = albumSequential,
        advanced = advanced,
        phraseBars = phraseBars,
    )
    if (!mixWindow) return plan
    return clampToMixWindow(
        plan,
        length = max(duration.orZero(), trackDurationSeconds(currentTrack)),
        playbackTime = max(0.0, currentTime.orZero()),
    )
}

private fun clampToMixWindow(plan: TransitionPlan, length: Double, playbackTime: Double): TransitionPlan {
    if (plan.blocked || length <= 0.0) return plan
    var result = plan
    val earliestStart = (length - MIX_WINDOW_SECONDS).coerceAtLeast(0.0)
    if (result.transitionStart < earliestStart) {
        val end = result.transitionEnd.coerceAtLeast(earliestStart)
        result = result.copy(
            shouldStart = playbackTime >= earliestStart,
            transitionStart = earliestStart,
            transitionEnd = end,
            fadeSeconds = (end - earliestStart).coerceAtLeast(0.0),
        )
    }
    if (result.incomingCueTime > MIX_WINDOW_SECONDS) {
        result = result.copy(incomingCueTime = MIX_WINDOW_SECONDS)
    }
    return result
}

private fun planTransitionInner(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    advanced: Boolean = false,
    phraseBars: Int = 0,
): TransitionPlan {
    val length = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return blocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        return standardTransition(length, playbackTime, standardFade, minFadeSeconds)
    }

    if (length < MIN_SMART_DURATION_SECONDS) {
        return blocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    val rankedMixOuts = rankMixOutCandidates(analysis, finalMixAnchor, length)
    val mixOutAnchor = rankedMixOuts.firstOrNull()
        ?.let { MixOutAnchor(it.time, it.type, it.discardedMusicSeconds) }
        ?: MixOutAnchor(finalMixAnchor, "content_end", 0.0)
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (isSpokenOrLive(currentTrack) || isSpokenOrLive(nextTrack)) {
        return blocked("blocked-speech-or-live")
    }

    if (!analysisReadyForTrack(analysis, currentTrack) ||
        !analysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        return standardTransition(
            length,
            playbackTime,
            standardFade,
            minFadeSeconds,
            "smart-analysis-fallback",
        )
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    val mixAnchor =
        if (playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }

    val policy = assessTransitionTier(analysis, nextAnalysis, advanced)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        val transitionStart = max(0.0, mixAnchor - standardFade)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = mixAnchor,
            fadeSeconds = mixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = IncomingCuePlanner.incomingStartPoint(nextAnalysis),
            policyReasons = policy.reasons,
            reason = if (started) "smart-plain-crossfade" else "before-plain-crossfade-window",
        )
    }

    val nextLength = max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack))

    if (TransitionPairCompatibility.isUnmixablePair(analysis, nextAnalysis)) {
        val transitionStart = max(0.0, finalMixAnchor - standardFade)
        val incomingCueTime = IncomingCuePlanner.incomingStartPoint(nextAnalysis)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = finalMixAnchor,
            fadeSeconds = finalMixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = incomingCueTime,
            incomingHandoffTime = incomingCueTime,
            vocalOverlap = plannedVocalOverlap(
                analysis = analysis,
                nextAnalysis = nextAnalysis,
                transitionStart = transitionStart,
                transitionEnd = finalMixAnchor,
                incomingCueTime = incomingCueTime,
                incomingPlaybackRate = 1.0,
            ),
            policyReasons = policy.reasons + "unmixable-pair",
            reason = if (started) "smart-unmixable-crossfade" else "before-unmixable-crossfade",
        )
    }

    if (phraseBars == PHRASE_BARS_SHORT || phraseBars == PHRASE_BARS_LONG) {
        phraseBarPlan(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            length = length,
            nextLength = nextLength,
            phraseBars = phraseBars,
            mixAnchor = mixAnchor,
            advanced = advanced,
        )?.let { plan ->
            if (playbackTime < plan.transitionEnd) {
                val started = playbackTime >= plan.transitionStart
                return IncomingCuePlanner.capIncomingSkip(
                    plan.copy(
                        shouldStart = started,
                        policyReasons = policy.reasons,
                        reason = if (started) "smart-phrase-$phraseBars-bar" else "before-phrase-$phraseBars-bar",
                    ),
                    nextAnalysis,
                    nextLength,
                )
            }
        }
    }

    phraseSwitch(analysis, nextAnalysis, length, nextLength, advanced)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return IncomingCuePlanner.capIncomingSkip(
                plan.copy(
                    shouldStart = started,
                    policyReasons = policy.reasons,
                    reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
                ),
                nextAnalysis,
                nextLength,
            )
        }

    val traits = if (advanced) TransitionPairCompatibility.PairTraits(analysis, nextAnalysis) else null
    val primary = mixPlan(analysis, nextAnalysis, mixAnchor, mixOutAnchor.type, nextLength, traits)
    val plan = if (traits != null &&
        primary.vocalOverlap > VOCAL_REROUTE_OVERLAP &&
        playbackTime < primary.transitionStart
    ) {
        rankedMixOuts.asSequence()
            .filter { abs(it.time - mixAnchor) > 1.0 && it.time <= length }
            .take(MAX_REROUTE_CANDIDATES)
            .map { mixPlan(analysis, nextAnalysis, it.time, it.type, nextLength, traits) }
            .filter { playbackTime < it.transitionStart }
            .minByOrNull { it.vocalOverlap }
            ?.takeIf { it.vocalOverlap <= primary.vocalOverlap * VOCAL_REROUTE_GAIN }
            ?: primary
    } else {
        primary
    }
    val started = playbackTime >= plan.transitionStart
    return IncomingCuePlanner.capIncomingSkip(
        plan.copy(
            shouldStart = started,
            policyReasons = policy.reasons,
            reason = if (started) plan.reason else "before-${plan.reason}",
        ),
        nextAnalysis,
        nextLength,
    )
}

private fun mixPlan(
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
