

package com.bxane.nuvx.playback.smart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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


@Suppress("UNUSED_PARAMETER")
internal fun incomingRateFor(mediaRatio: Double, advanced: Boolean): Double =
    roundRate(clamp(mediaRatio, MIN_INCOMING_RATE, MAX_INCOMING_RATE))

internal fun outgoingRateFor(mediaRatio: Double, advanced: Boolean): Double =
    1.0

internal fun roundRate(rate: Double): Double = (rate * 10000).roundToInt() / 10000.0

internal fun onDownbeatBefore(analysis: TrackAnalysis, time: Double, tolerance: Double): Double =
    IncomingCuePlanner.timedValueNearOrBefore(analysis.downbeats, time, tolerance) ?: time

private const val VOCAL_REROUTE_OVERLAP = 0.3
private const val VOCAL_REROUTE_GAIN = 0.5
private const val MAX_REROUTE_CANDIDATES = 3

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
