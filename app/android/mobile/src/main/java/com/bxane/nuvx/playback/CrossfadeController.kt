package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.playback.loopmix.LoopMixPort
import com.bxane.nuvx.playback.loopmix.LoopRegion
import com.bxane.nuvx.playback.smart.MixWaveform
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToLong

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

    private val readAhead = CrossfadeReadAheadController(TAG)

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

    private val blendPublisher = CrossfadeBlendPublisher(phaseLock)

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
            val held = readAhead.heldAtEnd ?: return
            if (held !== listeningTo) return
            val into = incoming
            if (phase == Phase.FADING && !handedOff && into != null) {
                handOff(held, into)
                return
            }
            readAhead.releaseHold()
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
        readAhead.releaseHold()
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        mixPresentation.clear()
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
        mixPresentation.expirePreview()

        if (speedController.isEasing && phase == Phase.IDLE) speedController.driveEase()

        when (phase) {
            Phase.IDLE -> transitionPlanner.considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.SLEEP_FADE -> sleepFade.drive()
            Phase.BAILING -> driveBail()
            Phase.LOOP_ARMING -> loopMix.driveArming(armDeadline)
            Phase.LOOP_RUNNING -> loopMix.driveMix()
        }
    }

    fun plannedTransitionFor(mediaId: String): Long? =
        transitionPlanner.plannedTransitionFor(mediaId)

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

        readAhead.holdAtEnd(out)

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
                mixPresentation.prefetch(
                    outItem, inItem, outDuration, trackInfoResolver.queuedDurationMs(inItem),
                    geometry.outStart, geometry.outEnd, geometry.inStart, geometry.ratio,
                )
            }
        }

        phase = Phase.ARMING
        return true
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
        if (isRealMix()) mixPresentation.publishView(out, into)
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

        if (readAhead.heldAtEnd === out && out.mediaItemCount > out.currentMediaItemIndex + 1) {
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
        blendPublisher.publish(
            out = out,
            into = into,
            smartFadeActive = smartFadeActive,
            handedOff = handedOff,
            outgoingAnalysis = outgoingAnalysis,
            incomingAnalysis = incomingAnalysis,
            positionMs = ::positionMs,
            force = force,
        )
    }

    private val mixPresentation = CrossfadeMixPresentationController(
        scope = scope,
        mixWaveform = mixWaveform,
        tag = TAG,
        envelope = envelope,
        currentRender = { render },
        updateRender = { render = it },
        renderFor = { plan, advanced -> this.renderFor(plan, advanced) },
        isIdle = { phase == Phase.IDLE },
        fadeEndTimeMs = { fadeEndMs },
        configuredFadeMs = { fadeMs },
        currentMediaRatio = { mediaRatio },
        incomingCueMs = { incomingCueTimeMs },
        currentPlan = { planInFlight },
        outgoingTrackAnalysis = { outgoingAnalysis },
        incomingTrackAnalysis = { incomingAnalysis },
    )

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
        holdAtEnd = readAhead::holdAtEnd,
        handOff = ::handOff,
        finish = ::finish,
        phaseLabel = { phase.toString() },
    )

    private val transitionPlanner = CrossfadeTransitionPlanner(
        active = active,
        analysisFor = analysisFor,
        requestAnalysis = requestAnalysis,
        versionSwapActive = versionSwapActive,
        nextStreamReady = nextStreamReady,
        isIdle = { phase == Phase.IDLE },
        configuredFadeMs = ::configuredFadeMs,
        renderFor = ::renderFor,
        begin = { start ->
            begin(
                start.fade,
                endMs = start.endMs,
                smart = start.smart,
                cueTimeMs = start.cueTimeMs,
                playbackRate = start.playbackRate,
                outgoingRate = start.outgoingRate,
                beatMs = start.beatMs,
                renderStyle = start.renderStyle,
                plan = start.plan,
            )
        },
        trackInfoResolver = trackInfoResolver,
        readAhead = readAhead,
        sleepFade = sleepFade,
        speedController = speedController,
        mixPresentation = mixPresentation,
        loopPlanner = loopPlanner,
        loopMix = loopMix,
        tag = TAG,
    )

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
        lastSmartPlan = { transitionPlanner.lastSmartPlan },
        lastSmartPlanStartMs = { transitionPlanner.lastSmartPlanStartMs },
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
        mixPresentation.clear()
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
        mixPresentation.clear()
        readAhead.releaseHold()
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
        const val TAG = "NuvxCrossfade"

        const val BAIL_MS = 120L

        const val ARM_TIMEOUT_MS = 12_000L

        const val HANDOFF_GUARD_MS = 400L

        const val IDLE_STEP_MS = 250L

        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L

        const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L

        const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
        const val BAIL_STEP_MS = 15L

        const val LOOP_STEP_MS = 20L

    }
}
