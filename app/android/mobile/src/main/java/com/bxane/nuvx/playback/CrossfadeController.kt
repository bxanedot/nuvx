package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.data.settings.TransitionWindow
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.playback.loopmix.LoopMixPort
import com.bxane.nuvx.playback.loopmix.LoopRegion
import com.bxane.nuvx.playback.smart.CrossfadeMode
import com.bxane.nuvx.playback.smart.FilterTransitionVariant
import com.bxane.nuvx.playback.smart.MixWaveform
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import com.bxane.nuvx.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToLong
import java.util.Locale

@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer,
    private val standby: () -> ExoPlayer,
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    private val analysisFor: (MediaItem) -> TrackAnalysis = { TrackAnalysis() },
    private val requestAnalysis: (MediaItem, Long) -> Unit = { _, _ -> },
    private val filters: TransitionFilters = TransitionFilters.None,
    private val analysisRunningFor: (MediaItem) -> Boolean = { false },
    private val onArmIncoming: (MediaItem, String?) -> Unit = { _, _ -> },
    private val onBlendHeadroom: (Float) -> Unit = {},
    private val versionSwapActive: () -> Boolean = { false },
    private val onSettled: () -> Unit = {},
    private val nextStreamReady: (MediaItem) -> Boolean = { true },
    mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands? = { _, _, _, _, _ -> null },
    private val loopPortOf: (ExoPlayer) -> LoopMixPort? = { null },
    private val loopRegion: (MediaItem, Long, Double, Double, Int) -> LoopRegion? = { _, _, _, _, _ -> null },
    private val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>? = { _, _ -> null },
    private val loudnessGain: (String) -> Float = { 1f },
    private val onMixPositionJump: (wrapped: Boolean) -> Unit = {},
) {
    private val trackInfoResolver = CrossfadeTrackInfoResolver(active)

    private enum class Phase {
        IDLE,

        ARMING,

        FADING,

        SLEEP_FADE,

        BAILING,

        LOOP_ARMING,

        LOOP_RUNNING,
    }

    private var phase = Phase.IDLE

    private var outgoing: ExoPlayer? = null

    private var incoming: ExoPlayer? = null

    private var handedOff = false

    private var queuedItemCount = 0

    private var heldAtEnd: ExoPlayer? = null

    private var holdWanted = false

    private var outgoingItemIndex = C.INDEX_UNSET

    private var listeningTo: ExoPlayer? = null
    private var tickerJob: Job? = null

    private var fadeMs = 0L

    private var fadeEndMs = 0L

    private var smartFadeActive = false

    private var incomingCueTimeMs: Long = 0L

    private var incomingPlaybackRate: Double = 1.0

    private var outgoingPlaybackRate: Double = 1.0

    private val mediaRatio: Double get() = incomingPlaybackRate / outgoingPlaybackRate

    private var render = CrossfadeRender()

    private val envelope = CrossfadeEnvelope(
        filters = filters,
        handedOff = { handedOff },
        currentRender = { render },
    )

    private val sleepFade = CrossfadeSleepFadeController(
        active = active,
        outgoing = { outgoing },
        envelope = envelope,
        onStart = { out, fade ->
            outgoing = out
            incoming = null
            handedOff = false
            Log.d(TAG, "sleep timer due: fading out over ${fade}ms instead of transitioning")
            phase = Phase.SLEEP_FADE
        },
        onFinish = {
            outgoing = null
            phase = Phase.IDLE
        },
    )

    private var planInFlight: TransitionPlan? = null

    private var lastMarkerVerdict = ""

    private var fadeStartedAt = 0L

    private var outgoingAnalysis = TrackAnalysis()
    private var incomingAnalysis = TrackAnalysis()
    private var bailStartedAt = 0L
    private var armDeadline = 0L

    private var settledAt = 0L

    private var bailFromGain = 0f

    private var lastPlanVerdict = ""

    private val outClock = CrossfadePositionClock(HALF_TICK_NANOS, MAX_EXTRAPOLATION_NANOS)
    private val inClock = CrossfadePositionClock(HALF_TICK_NANOS, MAX_EXTRAPOLATION_NANOS)

    private fun positionMs(player: ExoPlayer): Double =
        if (player === incoming) inClock.read(player) else outClock.read(player)

    private val phaseLock = CrossfadePhaseLockController(
        incomingBaseSpeed = { (AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat() },
        incomingPlaybackRate = { incomingPlaybackRate },
        mediaRatio = { mediaRatio },
        outgoingAnalysis = { outgoingAnalysis },
        incomingAnalysis = { incomingAnalysis },
        positionMs = { positionMs(it) },
    )

    private val speedController = CrossfadeSpeedController(
        scope = scope,
        active = active,
        isIdle = { phase == Phase.IDLE },
    )

    fun isTransitioning(): Boolean = phase != Phase.IDLE

    fun applyPlaybackSpeed(speed: Float) {
        if (isTransitioning()) return
        for (player in listOf(active(), standby()).distinct()) {
            player.setPlaybackSpeed(speed)
        }
    }

    fun msSinceTransition(): Long? =
        settledAt.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) bail()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> ranOutBeforeHandoff()
            }
        }

        override fun onPlayerError(error: PlaybackException) = bail()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            val held = heldAtEnd ?: return
            if (held !== listeningTo) return
            val into = incoming
            if (phase == Phase.FADING && !handedOff && into != null) {
                handOff(held, into)
                return
            }
            releaseHold()
            if (phase == Phase.ARMING) bail()
            held.play()
        }
    }

    private fun ranOutBeforeHandoff() {
        if (handedOff) return
        when (phase) {
            Phase.ARMING, Phase.LOOP_ARMING, Phase.LOOP_RUNNING -> bail()
            Phase.FADING -> {
                val out = outgoing ?: return bail()
                val into = incoming ?: return bail()
                Log.d(TAG, "outgoing ran on into the next track before the handoff")
                out.volume = 0f
                handOff(out, into)
            }
            else -> Unit
        }
    }

    private fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(listener)
        target.addListener(listener)
        listeningTo = target
    }

    fun start() {
        listenTo(active())
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.SLEEP_FADE -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                        Phase.LOOP_ARMING -> ARM_STEP_MS
                        Phase.LOOP_RUNNING -> LOOP_STEP_MS
                    },
                )
            }
        }
    }

    fun release() {
        tickerJob?.cancel()
        tickerJob = null
        loopMix.release()
        speedController.endEase(clickless = false)
        releaseHold()
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        mixWaves.clearMixView()
        AppSettings.smartMixPreview.value = null
        filters.open()
        filters.parkEchoes()
        onBlendHeadroom(1f)
    }

    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    private fun tick() {
        if (phase == Phase.FADING || phase == Phase.BAILING) {
            if (handedOff) {
                outgoing?.playWhenReady = incoming?.playWhenReady ?: true
            } else {
                outgoing?.playWhenReady?.let { incoming?.playWhenReady = it }
            }
        }

        analysisStatus.publish()
        automixDetail.publish()
        expireMixPreview()

        if (speedController.isEasing && phase == Phase.IDLE) speedController.driveEase()

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.SLEEP_FADE -> sleepFade.drive()
            Phase.BAILING -> driveBail()
            Phase.LOOP_ARMING -> loopMix.driveArming(armDeadline)
            Phase.LOOP_RUNNING -> loopMix.driveMix()
        }
    }

    private fun considerAutoTransition() {
        val player = active()
        if (!player.isPlaying) {
            refreshMarkerWhilePaused(player)
            return
        }
        holdWanted = false
        planAutoTransition(player)
        if (!holdWanted && phase == Phase.IDLE) releaseHold()
    }

    private fun refreshMarkerWhilePaused(player: ExoPlayer) {
        if (!AppSettings.smartFadeEnabled.value) return
        val now = SystemClock.elapsedRealtime()
        val step = if (AppSettings.mixViewOpen.value) IDLE_STEP_MS else PAUSED_MARKER_STEP_MS
        if (now - pausedMarkerAt < step) return
        pausedMarkerAt = now
        if (versionSwapActive() || ListenTogether.state.value.inParty) return
        if (!player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val item = player.currentMediaItem ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: trackInfoResolver.queuedDurationMs(item).takeIf { it > 0L }
            ?: (analysisFor(item).duration * 1000).roundToLong().takeIf { it > 0L }
            ?: return
        considerSmartTransition(duration, markerOnly = true)
    }

    private var pausedMarkerAt = 0L

    fun plannedTransitionFor(mediaId: String): Long? =
        plannedTransition?.takeIf { it.first == mediaId }?.second

    private var plannedTransition: Pair<String, Long>? = null

    private fun planAutoTransition(player: ExoPlayer) {
        plannedTransition = null
        if (versionSwapActive()) return
        if (ListenTogether.state.value.inParty) {
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        if (BitPerfectOutput.status.value.active) {
            AppSettings.smartTransitionWindow.value = null
            return
        }
        if (!player.hasNextMediaItem()) {
            AppSettings.smartTransitionWindow.value = null
            return
        }

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (AppSettings.smartFadeEnabled.value) requestAnalysisAround(player, duration)
            AppSettings.smartTransitionWindow.value = null
            return
        }

        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(duration)
            return
        }

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return

        player.currentMediaItem?.mediaId?.let { plannedTransition = it to duration - fade }
        val remaining = duration - player.currentPosition
        if (SleepTimer.afterTrack.value) {
            if (remaining > fade) return
            sleepFade.begin(fade)
            return
        }
        holdAhead(player, duration)
        if (remaining > fade + ARM_LEAD_MS) return

        begin(fade, endMs = duration, smart = false)
    }

    private fun considerSmartTransition(duration: Long, markerOnly: Boolean = false) {
        val player = active()
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) {
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        val nextDuration = trackInfoResolver.nextItemDurationMs(nextIndex, nextItem)

        requestAnalysisAround(player, duration)

        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }
            ?.div(1000.0)
            ?: DEFAULT_SMART_FALLBACK_SECONDS

        val currentAnalysis = analysisFor(currentItem)
        val nextAnalysis = analysisFor(nextItem)
        val analysisState = AppSettings.smartAnalysis.value
        val advanced = true

        val plan = planTransition(
            analysis = currentAnalysis,
            nextAnalysis = nextAnalysis,
            currentTrack = currentItem.toTransitionInfo(duration),
            nextTrack = nextItem.toTransitionInfo(nextDuration),
            currentTime = player.currentPosition / 1000.0,
            duration = duration / 1000.0,
            fadeSeconds = fallbackSeconds,
            mode = CrossfadeMode.SMART,
            advanced = advanced,
        )
        val verdict = "${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}" +
            "|cue=${plan.incomingCueTime}|rate=${plan.incomingPlaybackRate}" +
            "|vocalOverlap=${"%.2f".format(Locale.ROOT, plan.vocalOverlap)}" +
            "|advanced=$advanced|lock=${plan.phaseLock}|variant=${plan.filterVariant}|echo=${plan.echoSeconds}" +
            "|blocked=${plan.blocked}|policy=${plan.policyReasons.joinToString(",")}"
        if (verdict != lastPlanVerdict) {
            lastPlanVerdict = verdict
            Log.d(
                TAG,
                "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        lastSmartPlan = plan
        lastSmartPlanStartMs = (plan.transitionStart * 1000).roundToLong()
        publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, plan, advanced)

        val loop = loopPlanner.planFor(
            player, currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration, analysisState,
        )
        if (loop != null && nextStreamReady(nextItem)) {
            val loopStartMs = (loop.out.loopStart * 1000).roundToLong()
            AppSettings.smartTransitionWindow.value = TransitionWindow(
                start = (loopStartMs.toFloat() / duration).coerceIn(0f, 1f),
                end = 1f,
            )
            if (markerOnly) return
            plannedTransition = currentItem.mediaId to loopStartMs
            holdAhead(player, duration)
            if (loopStartMs - player.currentPosition > LOOP_ARM_LEAD_MS) return
            loopMix.begin(player, loop, currentItem, nextItem, duration, nextDuration)
            return
        }

        val markable = !plan.blocked &&
            plan.markerVisible &&
            duration > 0L &&
            analysisState.current == TrackAnalysisState.ANALYSED &&
            analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON &&
            nextStreamReady(nextItem)
        val markerVerdict = when {
            markable -> "shown"
            plan.blocked -> "hidden: plan blocked (${plan.reason})"
            !plan.markerVisible -> "hidden: plan has no marker (${plan.reason})"
            duration <= 0L -> "hidden: no duration"
            analysisState.current != TrackAnalysisState.ANALYSED -> "hidden: current is ${analysisState.current}"
            analysisState.next !in MEASURED_ENOUGH_TO_ENTER_ON -> "hidden: next is ${analysisState.next}"
            else -> "hidden: next stream not ready"
        }
        if (markerVerdict != lastMarkerVerdict) {
            lastMarkerVerdict = markerVerdict
            Log.d(
                TAG,
                "marker ${currentItem.mediaId} \"${currentItem.mediaMetadata.title}\" -> " +
                    "${nextItem.mediaId} \"${nextItem.mediaMetadata.title}\": $markerVerdict",
            )
        }
        AppSettings.smartTransitionWindow.value = if (markable) {
            TransitionWindow(
                start = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
                end = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
            )
        } else {
            null
        }

        if (plan.blocked || markerOnly) return

        val fade = plan.fadeMs
        if (fade <= 0L) return

        val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
        plannedTransition = currentItem.mediaId to transitionStartMs
        val remaining = transitionStartMs - player.currentPosition
        if (remaining <= MIX_PREFETCH_MS && currentAnalysis.isUsable && nextAnalysis.isUsable) {
            mixWaves.prefetchMixWaves(
                currentItem, nextItem, duration, nextDuration,
                plan.transitionStart, plan.transitionEnd, plan.incomingCueTime,
                plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01),
            )
        }
        if (SleepTimer.afterTrack.value) {
            if (remaining > 0L) return
            sleepFade.begin(fade)
            return
        }
        holdAhead(player, duration)
        val beatMs = (plan.beatSeconds * 1000).roundToLong()
        val rampMs = if (beatMs > 0L) speedController.rampStepsFor(plan.outgoingPlaybackRate) * beatMs else 0L
        if (remaining > ARM_LEAD_MS + rampMs) return

        begin(
            fade,
            endMs = (plan.transitionEnd * 1000).roundToLong(),
            smart = true,
            cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
            playbackRate = plan.incomingPlaybackRate,
            outgoingRate = plan.outgoingPlaybackRate,
            beatMs = beatMs,
            renderStyle = renderFor(plan, advanced),
            plan = plan,
        )
    }

    private fun renderFor(plan: TransitionPlan, advanced: Boolean) = CrossfadeRender(
        style = plan.transitionStyle,
        bassSwap = plan.bassSwap,
        bassSwapFraction = plan.bassSwapFraction,
        filterSweep = plan.filterSweep,
        filterVariant = plan.filterVariant,
        vocalOverlap = plan.vocalOverlap,
        advanced = advanced,
        beats = if (plan.beatSeconds > 0) plan.fadeSeconds / plan.beatSeconds else 0.0,
        lockPhase = plan.phaseLock,
        echoSeconds = plan.echoSeconds,
    )

    private fun requestAnalysisAround(player: ExoPlayer, duration: Long) {
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return
        requestAnalysis(currentItem, duration)
        requestAnalysis(nextItem, trackInfoResolver.nextItemDurationMs(nextIndex, nextItem))
    }

    private fun begin(
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        outgoingRate: Double = 1.0,
        beatMs: Long = 0L,
        renderStyle: CrossfadeRender = CrossfadeRender(),
        plan: TransitionPlan? = null,
    ): Boolean {
        val out = active()
        val into = standby()
        if (out === into) return false
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        speedController.endEase(clickless = false)

        fadeMs = fade
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        incomingPlaybackRate = playbackRate
        outgoingPlaybackRate = outgoingRate
        val rampSteps = speedController.beginOutgoingRamp(outgoingRate, beatMs, endMs, fade)
        render = renderStyle
        planInFlight = plan
        armDeadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS + rampSteps * beatMs
        handedOff = false
        outgoing = out
        incoming = into
        outgoingItemIndex = out.currentMediaItemIndex

        val t0 = SystemClock.elapsedRealtimeNanos()
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        val copyDurationUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000L
        queuedItemCount = items.size
        val perTrackUs = if (items.isNotEmpty()) copyDurationUs.toDouble() / items.size else 0.0
        Log.d(
            TAG,
            "begin() queue copy: ${items.size} tracks in ${copyDurationUs}µs (${String.format(java.util.Locale.US, "%.2f", perTrackUs)}µs/track)",
        )

        Log.d(
            TAG,
            "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                "cue=${incomingCueTimeMs}ms rate=$incomingPlaybackRate at=${out.currentPosition}ms " +
                "style=${render.style} bassSwap=${render.bassSwap}@${render.swapAt} " +
                "advanced=${render.advanced} lock=${render.phaseLock} handoff=${render.handoffAt} " +
                "echo=${if (render.echo) "${render.echoSecondsF}s@${render.echoAt}" else "off"} " +
                "sweep=${render.filterSweep}",
        )

        holdAtEnd(out)

        into.skipSilenceEnabled = out.skipSilenceEnabled
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed((AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat())
        into.volume = 0f
        val setNanos0 = SystemClock.elapsedRealtimeNanos()
        into.setMediaItems(items, nextIndex, incomingCueTimeMs)
        val setDurationUs = (SystemClock.elapsedRealtimeNanos() - setNanos0) / 1000L
        Log.d(TAG, "standby setMediaItems: ${items.size} tracks in ${setDurationUs}µs")
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            onArmIncoming(item, afterId)
        }
        into.playWhenReady = false
        into.prepare()
        if (smart && plan != null) {
            val outItem = items.getOrNull(outgoingItemIndex)
            val inItem = items.getOrNull(nextIndex)
            if (outItem != null && inItem != null) {
                val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val geometry = CrossfadeGeometry.playback(
                    fadeEndMs,
                    fadeMs,
                    render.advanced,
                    mediaRatio,
                    incomingCueTimeMs,
                )
                mixWaves.prefetchMixWaves(
                    outItem, inItem, outDuration, trackInfoResolver.queuedDurationMs(inItem),
                    geometry.outStart, geometry.outEnd, geometry.inStart, geometry.ratio,
                )
            }
        }

        phase = Phase.ARMING
        return true
    }

    private fun holdAtEnd(out: ExoPlayer) {
        if (heldAtEnd === out) return
        val duration = out.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val left = duration - out.currentPosition
        if (left <= maxReadAheadMs(out)) {
            Log.d(TAG, "end not held: ${left}ms left, may already be reading on (${maxReadAheadMs(out)}ms)")
            return
        }
        releaseHold()
        out.pauseAtEndOfMediaItems = true
        heldAtEnd = out
        Log.d(TAG, "end held at ${left}ms left")
    }

    private fun holdAhead(player: ExoPlayer, duration: Long) {
        val left = duration - player.currentPosition
        val limit = maxReadAheadMs(player)
        if (left > limit + HOLD_AHEAD_MS) return
        holdWanted = true
        if (left > limit) holdAtEnd(player)
    }

    private fun releaseHold() {
        heldAtEnd?.pauseAtEndOfMediaItems = false
        heldAtEnd = null
    }

    private fun maxReadAheadMs(player: ExoPlayer): Long {
        val speed = player.playbackParameters.speed.coerceAtLeast(1f)
        return (MEDIA3_READ_AHEAD_LIMIT_MS * speed).roundToLong() + HOLD_SAFETY_MS
    }

    private fun ranOn(out: ExoPlayer): Boolean =
        handedOff && outgoingItemIndex != C.INDEX_UNSET && out.currentMediaItemIndex != outgoingItemIndex

    private fun driveArming() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()
        if (!stillWorthFading()) return bail()
        if (!out.playWhenReady) return bail()

        val expired = SystemClock.elapsedRealtime() > armDeadline
        val ready = into.playbackState == Player.STATE_READY

        if (expired && !ready) return bail()

        val outgoingAt = if (fadeMs > 0L && fadeEndMs > 0L) {
            (out.currentPosition + envelope.tracks.outgoingLeadMs() - (fadeEndMs - fadeMs)).toFloat() / fadeMs
        } else {
            0f
        }
        envelope.rideFilters(0f, outgoingAt.takeIf { it > 0f }?.coerceAtMost(1f))
        if (speedController.hasOutgoingRamp) speedController.rampOutgoing(out)

        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - out.currentPosition <= fadeMs
        if (!atFadePoint) return
        if (ready) startFade()
    }

    private fun startFade() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()

        into.volume = 0f
        into.playWhenReady = true
        fadeStartedAt = SystemClock.elapsedRealtime()
        phaseLock.startFade(fadeStartedAt) {
            outClock.reset()
            inClock.reset()
        }
        if (speedController.outgoingRampIncomplete) speedController.finishOutgoingRamp(out)
        outgoingAnalysis = out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()
        incomingAnalysis = into.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()

        Log.d(TAG, "blend start at cue=${into.currentPosition}ms out=${out.currentPosition}ms")

        publishBlend(out, into, force = true)
        AppSettings.smartMixInProgress.value = isRealMix()
        if (isRealMix()) publishMixView(out, into)
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
    }

    private fun handOff(out: ExoPlayer, into: ExoPlayer) {
        if (handedOff) return
        reconcileQueue(out, into)

        Log.d(TAG, "handoff at in=${into.currentPosition}ms out=${out.currentPosition}ms")

        listenTo(into)
        handedOff = true
        onHandoff(out, into)

        if (heldAtEnd === out && out.mediaItemCount > out.currentMediaItemIndex + 1) {
            out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
        }
    }

    private fun reconcileQueue(out: ExoPlayer, into: ExoPlayer) {
        val appended = (queuedItemCount until out.mediaItemCount).map { out.getMediaItemAt(it) }
        if (appended.isEmpty()) return
        into.addMediaItems(appended)
        queuedItemCount = out.mediaItemCount
        Log.d(TAG, "reconciled ${appended.size} appended item(s) onto the incoming player")
    }

    private fun driveFade() {
        val out = outgoing ?: return bail()
        val player = incoming ?: return bail()
        val remainingIncoming = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(incomingCueTimeMs)
            ?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        val span = minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        val elapsed = (inClock.read(player) - incomingCueTimeMs).coerceAtLeast(0.0).toFloat()
        val wallSpan = if (render.advanced) span * mediaRatio.toFloat() else span.toFloat()
        val incomingProgress = (elapsed / wallSpan).coerceIn(0f, 1f)
        val outRemaining = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(out.currentPosition)
        val endDrivenProgress = outRemaining?.let {
            ((span - it).toFloat() / span).coerceIn(0f, 1f)
        } ?: 0f
        val progress = maxOf(incomingProgress, endDrivenProgress)

        if (!handedOff && player.playbackState == Player.STATE_IDLE) return bail()

        val incomingAt = ((elapsed + envelope.tracks.incomingLeadMs()) / wallSpan).coerceIn(0f, 1f)
        val outgoingAt = (progress + envelope.tracks.outgoingLeadMs() / span.toFloat()).coerceIn(0f, 1f)

        val rise = envelope.mixRise(progress)
        val fall = envelope.mixFall(progress)
        player.volume = rise
        out.volume = if (ranOn(out)) 0f else fall
        onBlendHeadroom(
            headroomFor(
                envelope.mixRise(outgoingAt),
                envelope.outgoingLevel(elapsed / wallSpan + envelope.tracks.outgoingLeadMs() / span.toFloat(), wallSpan),
            ),
        )
        if (render.phaseLock) phaseLock.hold(out, player, progress)

        if (!handedOff && (progress >= render.handoffAt || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
            handOff(out, player)
        }
        publishBlend(out, player)
        if (AppSettings.smartMixView.value != null) AppSettings.smartMixProgress.value = progress
        envelope.rideFilters(incomingAt, outgoingAt)

        val settingSwitchedOff = if (smartFadeActive) {
            !AppSettings.smartFadeEnabled.value
        } else {
            configuredFadeMs() <= 0L
        }
        val tailDone = !render.echo || elapsed / wallSpan >= render.echoAt + render.echoTailMs / wallSpan
        val done = (progress >= 1f && tailDone) || settingSwitchedOff
        if (done) {
            handOff(out, player)
            finish()
        }
    }

    private fun publishBlend(out: ExoPlayer, into: ExoPlayer, force: Boolean = false) {
        if (!smartFadeActive) return
        val session = if (handedOff) into else out
        val playing = session.isPlaying
        val last = AppSettings.smartMixBlend.value
        if (!force && last != null && !playing && !last.playing) return

        val sides = if (handedOff) {
            listOf(incomingAnalysis to into, outgoingAnalysis to out)
        } else {
            listOf(outgoingAnalysis to out, incomingAnalysis to into)
        }
        var beatMs = 0f
        var anchor = 0L
        for ((analysis, player) in sides) {
            if (!phaseLock.beatAt(analysis, positionMs(player) / 1000.0)) continue
            val rate = player.playbackParameters.speed.toDouble().takeIf { it > 0.0 } ?: 1.0
            val sinceBeat = phaseLock.beatOffsetSeconds
            beatMs = (phaseLock.beatIntervalSeconds * 1000.0 / rate).toFloat()
            anchor = System.nanoTime() - (sinceBeat / rate * 1e9).toLong()
            break
        }
        if (!force && last != null && last.playing == playing && CrossfadeGeometry.sameGrid(last, beatMs, anchor)) return
        AppSettings.smartMixBlend.value = MixBlend(
            beatMs = beatMs,
            beatAnchorNanos = anchor,
            playing = playing,
        )
    }

    private val mixWaves = CrossfadeWaveforms(scope, mixWaveform, TAG)

    private val mixPreview = CrossfadePreviewController(
        waves = mixWaves,
        envelope = envelope,
        currentRender = { render },
        updateRender = { render = it },
        renderFor = { plan, advanced -> this.renderFor(plan, advanced) },
        isIdle = { phase == Phase.IDLE },
    )

    private val mixViewPublisher = CrossfadeMixViewPublisher(
        waves = mixWaves,
        preview = mixPreview,
        envelope = envelope,
        currentRender = { render },
        fadeEndTimeMs = { fadeEndMs },
        configuredFadeMs = { fadeMs },
        currentMediaRatio = { mediaRatio },
        incomingCueMs = { incomingCueTimeMs },
        currentPlan = { planInFlight },
        outgoingTrackAnalysis = { outgoingAnalysis },
        incomingTrackAnalysis = { incomingAnalysis },
    )

    private fun publishMixPreview(
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        state: SmartAnalysis,
        plan: TransitionPlan,
        advanced: Boolean,
    ) {
        mixPreview.publish(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, state, plan, advanced)
    }

    private fun expireMixPreview() {
        mixPreview.expire()
    }

    private fun publishMixView(out: ExoPlayer, into: ExoPlayer) {
        mixViewPublisher.publish(out, into)
    }

    private fun driveBail() {
        val leaving = leavingOnBail()
        if (leaving == null) {
            finish()
            return
        }
        val progress = (SystemClock.elapsedRealtime() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            val fall = if (leaving === outgoing && ranOn(leaving)) 0f else bailFromGain * envelope.fallGain(progress)
            leaving.volume = fall
            onBlendHeadroom(headroomFor(1f, fall))
            return
        }
        finish()
    }


    private val loopPlanner = CrossfadeLoopPlanner(scope, syncedLines, loopPortOf, standby)

    private val loopMix = CrossfadeLoopMixController(
        scope = scope,
        standby = standby,
        loopPortOf = loopPortOf,
        loopRegion = loopRegion,
        loudnessGain = loudnessGain,
        endSpeedEase = { speedController.endEase(it) },
        filters = filters,
        onArmIncoming = onArmIncoming,
        onBlendHeadroom = onBlendHeadroom,
        onMixPositionJump = onMixPositionJump,
        planner = loopPlanner,
        isArming = { phase == Phase.LOOP_ARMING },
        isRunning = { phase == Phase.LOOP_RUNNING },
        markArming = { phase = Phase.LOOP_ARMING },
        markRunning = { phase = Phase.LOOP_RUNNING },
        activateSmartFade = { smartFadeActive = true },
        outgoing = { outgoing },
        setOutgoing = { outgoing = it },
        incoming = { incoming },
        setIncoming = { incoming = it },
        handedOff = { handedOff },
        setHandedOff = { handedOff = it },
        setOutgoingItemIndex = { outgoingItemIndex = it },
        setQueuedItemCount = { queuedItemCount = it },
        setArmDeadline = { armDeadline = it },
        holdAtEnd = ::holdAtEnd,
        handOff = ::handOff,
        finish = ::finish,
        phaseLabel = { phase.toString() },
    )

    private var lastSmartPlan: TransitionPlan? = null
    private var lastSmartPlanStartMs = 0L

    private val analysisStatus = CrossfadeAnalysisStatusController(
        active = active,
        analysisFor = analysisFor,
        analysisRunningFor = analysisRunningFor,
    )

    private val automixDetail = CrossfadeAutomixDetailController(
        active = active,
        loopSession = { loopMix.session },
        outgoing = { outgoing },
        loopArming = { phase == Phase.LOOP_ARMING },
        loopPlanner = loopPlanner,
        lastSmartPlan = { lastSmartPlan },
        lastSmartPlanStartMs = { lastSmartPlanStartMs },
        analysisFor = analysisFor,
        stateOf = analysisStatus::stateOf,
    )

    fun loopPositionMs(): Long? = loopMix.loopPositionMs()

    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopMix.bail("interrupted", settle = false)
            return
        }
        Log.d(TAG, "bail from $phase")
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        mixWaves.clearMixView()
        if (!handedOff && phase != Phase.FADING) {
            finish()
            return
        }
        filters.open()
        if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f
        bailFromGain = leavingOnBail()?.volume ?: 0f
        bailStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.BAILING
    }

    private fun leavingOnBail(): ExoPlayer? = if (handedOff) outgoing else incoming

    private fun finish() {
        val wasTransitioning = phase != Phase.IDLE
        if (phase != Phase.IDLE) {
            Log.d(TAG, "finish from $phase")
            settledAt = SystemClock.elapsedRealtime()
        }
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        mixWaves.clearMixView()
        releaseHold()
        if (handedOff && render.echo) outgoing?.volume = 0f
        filters.open()
        filters.parkEchoes()
        onBlendHeadroom(1f)
        render = CrossfadeRender()
        phaseLock.cancelNudge()

        if (handedOff) {
            incoming?.let {
                it.volume = 1f
                speedController.beginEase(it, incomingPlaybackRate, incomingAnalysis)
            }
            outgoing?.let(::retire)
        } else {
            outgoing?.let { out ->
                out.volume = 1f
                if (outgoingPlaybackRate != 1.0) {
                    speedController.beginEase(out, outgoingPlaybackRate, out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis())
                }
            }
            incoming?.let(::retire)
        }

        outgoing = null
        incoming = null
        handedOff = false
        outgoingItemIndex = C.INDEX_UNSET
        queuedItemCount = 0
        incomingCueTimeMs = 0L
        incomingPlaybackRate = 1.0
        outgoingPlaybackRate = 1.0
        speedController.clearOutgoingRamp()
        outgoingAnalysis = TrackAnalysis()
        incomingAnalysis = TrackAnalysis()
        phase = Phase.IDLE
        if (wasTransitioning) onSettled()
    }

    private fun stillWorthFading(): Boolean {
        val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
        return stillOn && (outgoing ?: active()).hasNextMediaItem()
    }

    private fun retire(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
    }

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    private fun isRealMix(): Boolean = smartFadeActive && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0 ||
            outgoingPlaybackRate != 1.0
        )

    private fun headroomFor(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain + outgoingGain
        return if (sum <= 1f) 1f else 1f / kotlin.math.sqrt(sum)
    }

    private companion object {
        const val MIX_PREFETCH_MS = 30_000L

        const val PAUSED_MARKER_STEP_MS = 1_000L

        const val TAG = "NuvxCrossfade"

        const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0

        const val BAIL_MS = 120L

        const val ARM_LEAD_MS = 4_000L

        const val MEDIA3_READ_AHEAD_LIMIT_MS = 10_000L

        const val HOLD_SAFETY_MS = 1_000L

        const val HOLD_AHEAD_MS = 3_000L

        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        const val ARM_TIMEOUT_MS = 12_000L

        const val HANDOFF_GUARD_MS = 400L

        const val IDLE_STEP_MS = 250L

        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L

        const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L

        const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
        const val BAIL_STEP_MS = 15L

        const val LOOP_STEP_MS = 20L

        const val LOOP_ARM_LEAD_MS = 12_000L
    }
}
