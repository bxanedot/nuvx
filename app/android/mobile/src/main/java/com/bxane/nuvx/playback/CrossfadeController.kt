package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixBlend
import com.bxane.nuvx.data.settings.MixDeck
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.data.settings.TransitionWindow
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.playback.loopmix.LoopMixPort
import com.bxane.nuvx.playback.loopmix.LoopMixRenderer
import com.bxane.nuvx.playback.loopmix.LoopMixSession
import com.bxane.nuvx.playback.loopmix.LoopRegion
import com.bxane.nuvx.playback.smart.CrossfadeMode
import com.bxane.nuvx.playback.smart.LoopMix
import com.bxane.nuvx.playback.smart.FILTER_ECHO_AT
import com.bxane.nuvx.playback.smart.FilterTransitionVariant
import com.bxane.nuvx.playback.smart.MixWaveform
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import com.bxane.nuvx.playback.smart.TransitionTrackInfo
import com.bxane.nuvx.playback.smart.echoTailSeconds
import com.bxane.nuvx.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
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

    private var rampSteps = 0
    private var rampStepMs = 0L
    private var rampStartMs = 0L
    private var rampStep = 0

    private var render = Render()

    private var planInFlight: TransitionPlan? = null

    private var lastMarkerVerdict = ""

    private class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
        val vocalOverlap: Double = 0.0,
        val advanced: Boolean = false,
        beats: Double = 0.0,
        lockPhase: Boolean = false,
        echoSeconds: Double = 0.0,
    ) {
        private val grid = if (advanced && beats >= 2.0) beats else 0.0
        val clash = vocalOverlap.coerceIn(0.0, 1.0)

        val echo = grid > 0 && echoSeconds > 0.0
        val echoSecondsF = echoSeconds.toFloat()

        val echoTailMs = (echoTailSeconds(echoSeconds) * 1000).toFloat()

        val phaseLock = advanced && lockPhase

        val swapAt = onBeat(bassSwapFraction.coerceIn(0.05, 0.95))

        val swapWidth = if (grid > 0) SWAP_BEATS / grid else 2 * BASS_SWAP_WIDTH
        val swapFrom = if (grid > 0) swapAt - swapWidth else swapAt - BASS_SWAP_WIDTH
        val entryOpenBy = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> ENTRY_OPEN_BY
                FilterTransitionVariant.BASS_HANDOFF -> 0.38
                FilterTransitionVariant.ECHO_RIDE -> 0.52
            },
        )
        val vocalEntryOpenBy = onBar(entryOpenBy + (VOCAL_SAFE_ENTRY_OPEN_BY - entryOpenBy) * clash)
        val blendEntryOpenBy = onBar(BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash)
        val blendExitFrom = onBar(BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash)
        val filterSwapAt = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> FILTER_BASS_SWAP_AT
                FilterTransitionVariant.BASS_HANDOFF -> 0.43
                FilterTransitionVariant.ECHO_RIDE -> 0.58
            },
        )
        val filterSwapAtF = filterSwapAt.toFloat()


        val echoAt = onBar(FILTER_ECHO_AT)
        val echoFrom = if (grid > 0) echoAt - 1.0 / grid else echoAt
        val echoKill = if (grid > 0) ECHO_KILL_BEATS / grid else 0.0

        val holdFaders = (1.0 - clash).toFloat()
        val swapAtF = swapAt.toFloat()

        val fallFrom = minOf(swapAtF, 0.5f, if (grid > 0) (1.0 - 4.0 / grid).toFloat() else 0.5f)
            .coerceAtLeast(0f)

        val handoffAt = when {
            !advanced -> HANDOFF_AT
            style == TransitionStyle.DJ_BLEND && bassSwap -> swapAtF
            style == TransitionStyle.DJ_FILTER && filterVariant == FilterTransitionVariant.BASS_HANDOFF -> filterSwapAtF
            else -> HANDOFF_AT
        }

        private fun onBeat(fraction: Double): Double =
            if (grid > 0) (round(fraction * grid) / grid).coerceIn(1.0 / grid, 1.0 - 1.0 / grid) else fraction

        private fun onBar(fraction: Double): Double =
            if (grid >= 8.0) {
                (round(fraction * grid / 4.0) * 4.0 / grid).coerceIn(4.0 / grid, 1.0 - 4.0 / grid)
            } else {
                onBeat(fraction)
            }
    }

    private var fadeStartedAt = 0L

    private var outgoingAnalysis = TrackAnalysis()
    private var incomingAnalysis = TrackAnalysis()
    private var bailStartedAt = 0L
    private var armDeadline = 0L

    private var sleepFadeMs = 0L

    private var sleepFadeStartPositionMs = 0L

    private var sleepFadeStartGain = 1f

    private var settledAt = 0L

    private var bailFromGain = 0f

    private var lastPlanVerdict = ""

    private var nudgeUntil = 0L

    private var lockQuietUntil = 0L

    private val lockErrors = DoubleArray(LOCK_SAMPLES)
    private var lockCount = 0

    private val beatScratch = DoubleArray(2)

    private val outClock = PositionClock()
    private val inClock = PositionClock()

    private class PositionClock {
        private var player: ExoPlayer? = null
        private var reported = 0L
        private var seenAt = 0L

        fun reset() {
            player = null
        }

        fun read(p: ExoPlayer): Double {
            val now = System.nanoTime()
            val position = p.currentPosition
            if (p !== player || position != reported) {
                seenAt = if (p === player) now - HALF_TICK_NANOS else now
                player = p
                reported = position
            }
            if (!p.isPlaying) return position.toDouble()
            val since = (now - seenAt).coerceIn(0L, MAX_EXTRAPOLATION_NANOS)
            return position + since / 1e6 * p.playbackParameters.speed
        }
    }

    private fun positionMs(player: ExoPlayer): Double =
        if (player === incoming) inClock.read(player) else outClock.read(player)

    private var easing: ExoPlayer? = null
    private var easeRate = 1.0
    private var easeStep = 0.0
    private var easeStepMs = 0L
    private var easeNextAtMs = 0L
    private var easeItemIndex = 0
    private var softSpeedJob: Job? = null

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
        loopBuildJob?.cancel()
        loopAlignJob?.cancel()
        loopSession?.let {
            it.outPort.disarm()
            it.inPort.disarm()
            closeLater(it.renderer)
        }
        loopSession = null
        endEase(clickless = false)
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

        publishAnalysisState()
        publishAutomixDetail()
        expireMixPreview()

        if (easing != null && phase == Phase.IDLE) driveEase()

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.SLEEP_FADE -> driveSleepFade()
            Phase.BAILING -> driveBail()
            Phase.LOOP_ARMING -> driveLoopArming()
            Phase.LOOP_RUNNING -> driveLoopMix()
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
            ?: queuedDurationMs(item).takeIf { it > 0L }
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
            beginSleepFadeOut(fade)
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
        val nextDuration = nextItemDurationMs(nextIndex, nextItem)

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

        val loop = loopPlanFor(
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
            beginLoop(player, loop, currentItem, nextItem, duration, nextDuration)
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
            beginSleepFadeOut(fade)
            return
        }
        holdAhead(player, duration)
        val beatMs = (plan.beatSeconds * 1000).roundToLong()
        val rampMs = if (beatMs > 0L) rampStepsFor(plan.outgoingPlaybackRate) * beatMs else 0L
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

    private fun renderFor(plan: TransitionPlan, advanced: Boolean) = Render(
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
        requestAnalysis(nextItem, nextItemDurationMs(nextIndex, nextItem))
    }

    private fun publishAnalysisState() {
        val player = active()
        val currentItem = player.currentMediaItem
        val nextIndex = player.nextMediaItemIndex
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = currentItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
            next = nextItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
        )
    }

    private fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = when {
        analysis.isUsable ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
        analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    private fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        val timeline = active().currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it }
        }
        return queuedDurationMs(item)
    }

    private fun queuedDurationMs(item: MediaItem): Long {
        val uri = item.localConfiguration?.uri ?: return 0L
        val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }

    private fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = mediaId,
        durationMs = durationMs,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

    private fun begin(
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        outgoingRate: Double = 1.0,
        beatMs: Long = 0L,
        renderStyle: Render = Render(),
        plan: TransitionPlan? = null,
    ): Boolean {
        val out = active()
        val into = standby()
        if (out === into) return false
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        endEase(clickless = false)

        fadeMs = fade
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        incomingPlaybackRate = playbackRate
        outgoingPlaybackRate = outgoingRate
        rampSteps = if (beatMs > 0L) rampStepsFor(outgoingRate) else 0
        rampStepMs = beatMs
        rampStartMs = endMs - fade - rampSteps * beatMs
        rampStep = 0
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
                val geometry = mixGeometry()
                mixWaves.prefetchMixWaves(
                    outItem, inItem, outDuration, queuedDurationMs(inItem),
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
            (out.currentPosition + tracks.outgoingLeadMs() - (fadeEndMs - fadeMs)).toFloat() / fadeMs
        } else {
            0f
        }
        rideFilters(0f, outgoingAt.takeIf { it > 0f }?.coerceAtMost(1f))
        if (rampSteps > 0) rampOutgoing(out)

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
        nudgeUntil = 0L
        lockCount = 0
        outClock.reset()
        inClock.reset()
        lockQuietUntil = fadeStartedAt + LOCK_START_MS
        if (rampSteps > 0 && rampStep < rampSteps) {
            rampStep = rampSteps
            out.setPlaybackSpeed((AppSettings.playbackSpeed.value * outgoingPlaybackRate).toFloat())
        }
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

        val incomingAt = ((elapsed + tracks.incomingLeadMs()) / wallSpan).coerceIn(0f, 1f)
        val outgoingAt = (progress + tracks.outgoingLeadMs() / span.toFloat()).coerceIn(0f, 1f)

        val rise = mixRise(progress)
        val fall = mixFall(progress)
        player.volume = rise
        out.volume = if (ranOn(out)) 0f else fall
        onBlendHeadroom(
            headroomFor(mixRise(outgoingAt), outgoingLevel(elapsed / wallSpan + tracks.outgoingLeadMs() / span.toFloat(), wallSpan)),
        )
        if (render.phaseLock) holdPhase(out, player, progress)

        if (!handedOff && (progress >= render.handoffAt || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
            handOff(out, player)
        }
        publishBlend(out, player)
        if (AppSettings.smartMixView.value != null) AppSettings.smartMixProgress.value = progress
        rideFilters(incomingAt, outgoingAt)

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
            if (!beatAt(analysis, positionMs(player) / 1000.0)) continue
            val rate = player.playbackParameters.speed.toDouble().takeIf { it > 0.0 } ?: 1.0
            val sinceBeat = beatScratch[0]
            beatMs = (beatScratch[1] * 1000.0 / rate).toFloat()
            anchor = System.nanoTime() - (sinceBeat / rate * 1e9).toLong()
            break
        }
        if (!force && last != null && last.playing == playing && sameGrid(last, beatMs, anchor)) return
        AppSettings.smartMixBlend.value = MixBlend(
            beatMs = beatMs,
            beatAnchorNanos = anchor,
            playing = playing,
        )
    }

    private class MixGeometry(val outStart: Double, val outEnd: Double, val inStart: Double, val inEnd: Double, val ratio: Double) {
        val outSpan get() = outEnd - outStart
        val context get() = outSpan * MIX_VIEW_CONTEXT
        val outFrom get() = outStart - context
        val outTo get() = outEnd + context
        val inFrom get() = inStart - context * ratio
        val inTo get() = inEnd + context * ratio
    }

    private fun mixGeometry(): MixGeometry {
        val outEnd = fadeEndMs / 1000.0
        val outStart = outEnd - (fadeMs / 1000.0).coerceAtLeast(0.5)
        val ratio = if (render.advanced) mediaRatio else 1.0
        val inStart = incomingCueTimeMs / 1000.0
        return MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
    }

    private val mixWaves = CrossfadeWaveforms(scope, mixWaveform, TAG)

    private var mixPreviewAt = 0L

    private var mixPreviewKey = ""

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
        if (!AppSettings.mixViewOpen.value || AppSettings.smartMixView.value != null) return
        mixPreviewAt = SystemClock.elapsedRealtime()
        val outStart = plan.transitionStart
        val outEnd = maxOf(plan.transitionEnd, outStart + 0.5)
        val inStart = plan.incomingCueTime
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
        val geometry = MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
        val currentAnalysing = state.current == TrackAnalysisState.WAITING || state.current == TrackAnalysisState.ANALYSING
        val nextAnalysing = state.next == TrackAnalysisState.WAITING || state.next == TrackAnalysisState.ANALYSING
        val beatSeconds = if (currentAnalysis.isUsable) plan.beatSeconds else 0.0
        val beats = if (beatSeconds > 0) (geometry.outSpan / beatSeconds).roundToInt() else 0

        mixWaves.prefetchMixWaves(
            currentItem, nextItem, duration, nextDuration, outStart, outEnd, inStart, ratio,
            wantOut = currentAnalysis.isUsable,
            wantIn = nextAnalysis.isUsable,
        )

        val ids = currentItem.mediaId to nextItem.mediaId
        val key = "${ids.first}|${ids.second}|${round(outStart * 20)}|${round(outEnd * 20)}|${round(inStart * 20)}|" +
            "$beats|$currentAnalysing|$nextAnalysing|${currentAnalysis.bpm}|${nextAnalysis.bpm}|" +
            "${currentAnalysis.key}|${nextAnalysis.key}|${plan.transitionStyle}|${plan.bassSwap}"
        if (key == mixPreviewKey && mixWaves.mixPreviewIds == ids && AppSettings.smartMixPreview.value != null) return
        mixPreviewKey = key

        val planned = renderFor(plan, advanced)
        val running = render
        render = planned
        val samples = MIX_VIEW_FADER_POINTS
        val wallSpan = (plan.fadeMs * if (planned.advanced) ratio else 1.0).toFloat()
        val fadeOut: FloatArray
        val fadeIn: FloatArray
        try {
            fadeOut = FloatArray(samples) { outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
            fadeIn = FloatArray(samples) { mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }
        } finally {
            render = running
        }

        fun deck(item: MediaItem, analysis: TrackAnalysis, durationMs: Long, analysing: Boolean, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = if (analysis.isUsable) analysis.bpm else 0.0,
            key = if (analysis.isUsable) analysis.key else "",
            durationMs = durationMs.takeIf { it > 0L } ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
            analysing = analysing,
        )

        mixWaves.mixPreviewIds = ids
        AppSettings.smartMixPreview.value = mixWaves.withWaves(
            MixTransitionView(
                outgoing = deck(currentItem, currentAnalysis, duration, currentAnalysing, geometry.outFrom, geometry.outTo),
                incoming = deck(nextItem, nextAnalysis, nextDuration, nextAnalysing, geometry.inFrom, geometry.inTo),
                outStart = outStart,
                outEnd = outEnd,
                inStart = geometry.inStart,
                inEnd = geometry.inEnd,
                beats = beats,
                beatMarks = beatMarks(currentAnalysis, outStart, outEnd, beatSeconds),
                fadeOut = fadeOut,
                fadeIn = fadeIn,
                bassSwapAt = when {
                    plan.transitionStyle == TransitionStyle.DJ_FILTER &&
                        plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF -> planned.filterSwapAtF
                    planned.bassSwap -> planned.swapAtF
                    else -> -1f
                },
            ),
            ids,
        )
    }

    private fun expireMixPreview() {
        if (AppSettings.smartMixPreview.value == null) return
        val stale = phase == Phase.IDLE && SystemClock.elapsedRealtime() - mixPreviewAt > MIX_PREVIEW_STALE_MS
        if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null && !stale) return
        AppSettings.smartMixPreview.value = null
        mixWaves.mixPreviewIds = null
        mixPreviewKey = ""
    }

    private fun beatMarks(analysis: TrackAnalysis, outStart: Double, outEnd: Double, beatSeconds: Double): FloatArray {
        if (beatSeconds <= 0) return FloatArray(0)
        val span = outEnd - outStart
        val anchor = analysis.downbeats.lastOrNull { it <= outStart } ?: outStart
        val first = anchor + ceil((outStart - anchor) / beatSeconds) * beatSeconds
        return generateSequence(first) { it + beatSeconds }
            .takeWhile { it <= outEnd + 1e-6 }
            .map { ((it - outStart) / span).toFloat() }
            .toList().toFloatArray()
    }

    private fun publishMixView(out: ExoPlayer, into: ExoPlayer) {
        val plan = planInFlight ?: return
        val outItem = out.currentMediaItem ?: return
        val inItem = into.currentMediaItem ?: return
        val geometry = mixGeometry()
        val outStart = geometry.outStart
        val outEnd = geometry.outEnd
        val outSpan = geometry.outSpan
        val beatSeconds = plan.beatSeconds
        val beats = if (beatSeconds > 0) (outSpan / beatSeconds).roundToInt() else 0
        val marks = beatMarks(outgoingAnalysis, outStart, outEnd, beatSeconds)
        val wallSpan = (fadeMs * if (render.advanced) mediaRatio else 1.0).toFloat()
        val samples = MIX_VIEW_FADER_POINTS
        val fadeOut = FloatArray(samples) { outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
        val fadeIn = FloatArray(samples) { mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }

        fun deck(item: MediaItem, player: ExoPlayer, analysis: TrackAnalysis, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = analysis.bpm,
            key = analysis.key,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
        )

        AppSettings.smartMixProgress.value = 0f
        mixWaves.mixViewIds = outItem.mediaId to inItem.mediaId
        AppSettings.smartMixPreview.value = null
        mixWaves.mixPreviewIds = null
        mixPreviewKey = ""
        AppSettings.smartMixView.value = MixTransitionView(
            outgoing = deck(outItem, out, outgoingAnalysis, geometry.outFrom, geometry.outTo),
            incoming = deck(inItem, into, incomingAnalysis, geometry.inFrom, geometry.inTo),
            outStart = outStart,
            outEnd = outEnd,
            inStart = geometry.inStart,
            inEnd = geometry.inEnd,
            beats = beats,
            beatMarks = marks,
            fadeOut = fadeOut,
            fadeIn = fadeIn,
            bassSwapAt = when {
                render.style == TransitionStyle.DJ_FILTER && render.filterVariant == FilterTransitionVariant.BASS_HANDOFF ->
                    render.filterSwapAtF
                render.bassSwap -> render.swapAtF
                else -> -1f
            },
        )
        mixWaves.republishWaves()
    }

    private fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (abs(beatMs - last.beatMs) > beatMs * TEMPO_TOLERANCE) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    private fun beginSleepFadeOut(fade: Long) {
        val out = active()
        sleepFadeMs = fade.coerceAtLeast(1L)
        sleepFadeStartPositionMs = out.currentPosition
        sleepFadeStartGain = out.volume
        outgoing = out
        incoming = null
        handedOff = false
        Log.d(TAG, "sleep timer due: fading out over ${fade}ms instead of transitioning")
        phase = Phase.SLEEP_FADE
    }

    private fun driveSleepFade() {
        val out = outgoing ?: return finishSleepFade()
        if (!SleepTimer.afterTrack.value) {
            out.volume = 1f
            outgoing = null
            phase = Phase.IDLE
            return
        }
        val elapsed = (out.currentPosition - sleepFadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / sleepFadeMs).coerceIn(0f, 1f)
        out.volume = sleepFadeStartGain * fallGain(progress)
        val done = progress >= 1f ||
            out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE
        if (done) finishSleepFade()
    }

    private fun finishSleepFade() {
        outgoing?.pause()
        outgoing?.volume = 1f
        SleepTimer.cancel()
        outgoing = null
        phase = Phase.IDLE
    }

    private fun driveBail() {
        val leaving = leavingOnBail()
        if (leaving == null) {
            finish()
            return
        }
        val progress = (SystemClock.elapsedRealtime() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            val fall = if (leaving === outgoing && ranOn(leaving)) 0f else bailFromGain * fallGain(progress)
            leaving.volume = fall
            onBlendHeadroom(headroomFor(1f, fall))
            return
        }
        finish()
    }


    private var loopSession: LoopMixSession? = null
    private var loopBuildJob: Job? = null
    private var loopAlignJob: Job? = null

    private var loopPlanKey: String? = null
    private var loopPlanned: LoopMix.Plan? = null

    private var loopRefusal: String? = null
    private var lastLoopVerdict = ""
    private var loopIncomingStarted = false
    private var lastLoopPositionMs = -1L
    private var lastLoopAnnounceAt = 0L
    private var lastLoopBeat = -1

    private class LyricSpans(val spans: List<LoopMix.Span>?)

    private val lyricSpans = java.util.concurrent.ConcurrentHashMap<String, LyricSpans>()
    private val lyricsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun askLyrics(item: MediaItem, durationMs: Long) {
        val id = item.mediaId
        if (!lyricsAsked.add(id)) return
        scope.launch(Dispatchers.IO) {
            val lines = runCatching { syncedLines(item, durationMs) }.getOrNull()
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(line.timeMs, end, blank = line.isGap || line.text.isBlank())
                    },
                )
            }
            lyricSpans[id] = LyricSpans(spans)
        }
    }

    private fun loopRefusalFor(player: ExoPlayer, current: MediaItem, next: MediaItem, state: SmartAnalysis): String? = when {
        !LoopMixRenderer.available -> "no renderer"
        AppSettings.playbackSpeed.value != 1f -> "playback speed is not 1x"
        player.skipSilenceEnabled -> "skip silence is on"
        SleepTimer.afterTrack.value -> "sleep timer"
        state.current != TrackAnalysisState.ANALYSED || state.next != TrackAnalysisState.ANALYSED -> "waiting on analysis"
        isSourceBacked(current) || isSourceBacked(next) -> "analysed off another recording"
        loopPortOf(player)?.supportsLoopMix != true -> "this output can't take it"
        loopPortOf(standby()) == null -> "no standby sink"
        else -> null
    }

    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    private fun loopPlanFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
        state: SmartAnalysis,
    ): LoopMix.Plan? {
        val pair = "${current.mediaId}->${next.mediaId}"
        loopRefusalFor(player, current, next, state)?.let { why ->
            noteLoopVerdict(pair, null, why)
            return null
        }
        askLyrics(current, durationMs)
        askLyrics(next, nextDurationMs)
        val outLyrics = lyricSpans[current.mediaId]
        val inLyrics = lyricSpans[next.mediaId]
        if (outLyrics == null || inLyrics == null) {
            noteLoopVerdict(pair, null, "waiting on lyrics")
            return null
        }
        val key = "$pair|${System.identityHashCode(currentAnalysis)}|${System.identityHashCode(nextAnalysis)}"
        val cached = loopPlanned
        if (key == loopPlanKey && (cached == null || cached.out.loopStart * 1000 - player.currentPosition >= LOOP_MIN_LEAD_MS)) {
            return cached
        }
        val why = StringBuilder()
        val plan = LoopMix.plan(
            out = currentAnalysis,
            outDuration = durationMs / 1000.0,
            inn = nextAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = player.currentPosition / 1000.0 + LOOP_SETUP_SECONDS,
            sungOut = outLyrics.spans,
            sungIn = inLyrics.spans,
            why = why,
        )
        loopPlanKey = key
        loopPlanned = plan
        val lyricsNote = "lyrics ${if (outLyrics.spans != null) "out" else "-"}/${if (inLyrics.spans != null) "in" else "-"}"
        noteLoopVerdict(pair, plan, if (plan == null) "$why; $lyricsNote" else null)
        return plan
    }

    private fun noteLoopVerdict(pair: String, plan: LoopMix.Plan?, refusal: String?) {
        loopRefusal = refusal
        val verdict = "$pair: " + (plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${describeLoop(it)}" } ?: "no loop mix ($refusal)")
        if (verdict == lastLoopVerdict) return
        lastLoopVerdict = verdict
        Log.d(TAG, "loop $verdict")
    }

    private fun describeLoop(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold"
    }

    private fun beginLoop(
        out: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        val into = standby()
        if (out === into) return
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val outPort = loopPortOf(out) ?: return
        val inPort = loopPortOf(into) ?: return
        endEase(clickless = false)
        phase = Phase.LOOP_ARMING
        smartFadeActive = true
        handedOff = false
        outgoing = out
        incoming = into
        outgoingItemIndex = out.currentMediaItemIndex
        armDeadline = SystemClock.elapsedRealtime() + LOOP_BUILD_TIMEOUT_MS
        holdAtEnd(out)
        val sampleRate = outPort.sampleRate
        val inLevel = (loudnessGain(inItem.mediaId) / loudnessGain(outItem.mediaId))
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        Log.d(
            TAG,
            "loop build ${outItem.mediaId}->${inItem.mediaId} at ${sampleRate}Hz, loop at " +
                "${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s, playhead ${out.currentPosition}ms",
        )
        loopBuildJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            val session = withContext(Dispatchers.Default) {
                runCatching {
                    LoopMixSession.build(plan, outItem.mediaId, inItem.mediaId, sampleRate, inLevel) { side, from, to ->
                        if (side == LoopMix.Side.OUT) {
                            loopRegion(outItem, durationMs, from, to, sampleRate)
                        } else {
                            loopRegion(inItem, nextDurationMs, from, to, sampleRate)
                        }
                    }
                }.onFailure { Log.w(TAG, "loop build failed", it) }.getOrNull()
            }
            if (phase != Phase.LOOP_ARMING || outgoing !== out) {
                session?.close()
                return@launch
            }
            if (session == null) {
                loopFallBack("the decks would not build")
                return@launch
            }
            Log.d(TAG, "loop decks built in ${SystemClock.elapsedRealtime() - began}ms")
            armLoop(session, out, into, nextIndex, outPort, inPort)
        }
    }

    private fun armLoop(
        session: LoopMixSession,
        out: ExoPlayer,
        into: ExoPlayer,
        nextIndex: Int,
        outPort: LoopMixPort,
        inPort: LoopMixPort,
    ) {
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        queuedItemCount = items.size
        into.skipSilenceEnabled = false
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        into.volume = 1f
        out.volume = 1f
        filters.open()
        onBlendHeadroom(1f)
        into.setMediaItems(items, nextIndex, (session.inStartTrack * 1000).roundToLong())
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            onArmIncoming(item, afterId)
        }
        into.playWhenReady = false
        inPort.armIncoming(LoopMixPort.InArm((session.handoverTrack * 1_000_000).roundToLong()))
        into.prepare()
        session.attach(outPort, inPort)
        loopSession = session
        loopIncomingStarted = false
        lastLoopPositionMs = -1L
        lastLoopBeat = -1
        Log.d(
            TAG,
            "loop armed: takeover at ${session.takeoverUs}us (stream offset ${outPort.streamOffsetUs}us), " +
                "incoming from ${"%.3f".format(Locale.ROOT, session.inStartTrack)}s, handover at " +
                "${"%.3f".format(Locale.ROOT, session.handoverTrack)}s, mix ${"%.1f".format(Locale.ROOT, session.clock.elementAt)}s",
        )
    }

    private fun driveLoopArming() {
        val out = outgoing ?: return loopBail("no outgoing player")
        if (!out.playWhenReady) return loopBail("paused before the loop")
        val session = loopSession
        if (session == null) {
            if (SystemClock.elapsedRealtime() > armDeadline) loopFallBack("the decks took too long")
            return
        }
        if (session.outPort.interrupted) return loopBail("the outgoing sink was flushed", settle = false)
        if (session.outPort.tookOver) {
            phase = Phase.LOOP_RUNNING
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = true
            scope.launch(Dispatchers.Default) {
                session.outPort.spliceLag()?.let { (lag, score) ->
                    Log.d(
                        TAG,
                        "loop splice: deck ${"%+.2f".format(Locale.ROOT, lag * 1000.0 / session.sampleRate)}ms " +
                            "against its song (r=${"%.3f".format(Locale.ROOT, score)})",
                    )
                }
            }
            return
        }
        val m = session.mixSeconds(System.nanoTime(), out.isPlaying) ?: return
        if (m > 1.0) loopBail("the sink never reached the loop")
    }

    private fun driveLoopMix() {
        val session = loopSession ?: return finish()
        val out = outgoing ?: return loopBail("no outgoing player")
        val into = incoming ?: return loopBail("no incoming player")
        val sessionPlayer = if (handedOff) into else out
        if (session.outPort.interrupted || (loopIncomingStarted && session.inPort.interrupted)) {
            return loopBail("a sink was flushed", settle = false)
        }
        if (!sessionPlayer.playWhenReady) return loopBail("paused", settle = true)
        val now = System.nanoTime()
        val m = session.mixSeconds(now, out.isPlaying) ?: return
        if (!loopIncomingStarted && m >= session.inStartAt) {
            into.playWhenReady = true
            loopIncomingStarted = true
        }
        if (loopIncomingStarted && loopAlignJob == null && into.isPlaying &&
            session.inPort.positionUs != C.TIME_UNSET && m >= session.inStartAt + LOOP_ALIGN_SETTLE_SECONDS
        ) {
            startLoopAlignment(session)
        }
        if (!handedOff && m >= session.clock.handoffAt) {
            handOff(out, into)
        }
        publishLoopBeat(session, m, now)
        announceLoopPosition()
        if (m >= session.clock.elementAt + LOOP_END_MARGIN_SECONDS) finishLoop()
    }

    private fun startLoopAlignment(session: LoopMixSession) {
        loopAlignJob = scope.launch {
            repeat(LOOP_ALIGN_ROUNDS) { round ->
                val samples = ArrayList<Double>(LOOP_ALIGN_SAMPLES)
                repeat(LOOP_ALIGN_SAMPLES) {
                    session.offsetNow(System.nanoTime())?.let(samples::add)
                    delay(LOOP_ALIGN_SAMPLE_MS)
                }
                if (samples.size < LOOP_ALIGN_SAMPLES / 2) return@launch
                samples.sort()
                val error = samples[samples.size / 2]
                val spread = samples.last() - samples.first()
                Log.d(
                    TAG,
                    "loop align $round: incoming ${"%+.2f".format(Locale.ROOT, error * 1000)}ms against the deck " +
                        "(spread ${"%.2f".format(Locale.ROOT, spread * 1000)}ms)",
                )
                session.recordAligned(error)
                if (LoopMixSession.aligned(error)) return@launch
                val port = session.inPort
                val lead = if (port.processedUs != C.TIME_UNSET && port.positionUs != C.TIME_UNSET) {
                    ((port.processedUs - port.positionUs).coerceAtLeast(0L)) / 1e6
                } else {
                    1.0
                }
                session.correct(error)
                delay(((lead + abs(error) + 0.4) * 1000).roundToLong().coerceIn(300L, 12_000L))
            }
            session.offsetNow(System.nanoTime())?.let {
                session.recordAligned(it)
                Log.d(TAG, "loop align: left at ${"%+.2f".format(Locale.ROOT, it * 1000)}ms")
            }
        }
    }

    private fun publishLoopBeat(session: LoopMixSession, m: Double, now: Long) {
        if (m < 0) return
        val (beat, _) = session.clock.beatAt(m)
        val times = session.clock.beatTimes
        if (beat == lastLoopBeat || beat !in 0 until times.size - 1) return
        lastLoopBeat = beat
        val beatMs = ((times[beat + 1] - times[beat]) * 1000).toFloat()
        val anchor = now - ((m - times[beat]) * 1e9).roundToLong()
        AppSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = true)
    }

    private fun announceLoopPosition() {
        val position = loopPositionMs() ?: return
        val now = SystemClock.elapsedRealtime()
        val wrapped = lastLoopPositionMs >= 0 && position < lastLoopPositionMs - LOOP_WRAP_MS
        lastLoopPositionMs = position
        if (wrapped || now - lastLoopAnnounceAt >= LOOP_ANNOUNCE_MS) {
            lastLoopAnnounceAt = now
            onMixPositionJump(wrapped)
        }
    }

    fun loopPositionMs(): Long? {
        if (phase != Phase.LOOP_RUNNING) return null
        val session = loopSession ?: return null
        val playing = outgoing?.isPlaying ?: return null
        val m = session.mixSeconds(System.nanoTime(), playing) ?: return null
        if (m < 0) return null
        val side = if (handedOff) LoopMix.Side.IN else LoopMix.Side.OUT
        val position = LoopMix.positionAt(session.clock, side, m) ?: return null
        return (position * 1000).roundToLong()
    }

    private fun finishLoop() {
        val session = loopSession
        Log.d(
            TAG,
            "loop mix done: incoming ${"%+.2f".format(Locale.ROOT, session?.alignedErrorMs ?: Double.NaN)}ms " +
                "against the deck at the handover",
        )
        loopAlignJob?.cancel()
        loopAlignJob = null
        loopSession = null
        session?.outPort?.disarm()
        session?.inPort?.disarm()
        finish()
        session?.renderer?.let(::closeLater)
    }

    private fun loopBail(reason: String, settle: Boolean = true) {
        val session = loopSession
        Log.d(TAG, "loop mix stops ($phase): $reason")
        loopBuildJob?.cancel()
        loopBuildJob = null
        loopAlignJob?.cancel()
        loopAlignJob = null
        loopSession = null
        val out = outgoing
        val into = incoming
        val wasHandedOff = handedOff
        val m = session?.mixSeconds(System.nanoTime(), playing = false)
        val tookOver = session?.outPort?.tookOver == true
        session?.outPort?.disarm()
        session?.inPort?.disarm()
        val target: Long? = when {
            session == null -> null
            wasHandedOff -> m?.let { LoopMix.positionAt(session.clock, LoopMix.Side.IN, it) }?.let { (it * 1000).roundToLong() }
            tookOver -> (m?.takeIf { it >= 0 }?.let { LoopMix.positionAt(session.clock, LoopMix.Side.OUT, it) }
                ?: session.plan.out.loopStart).let { (it * 1000).roundToLong() }
            else -> null
        }
        finish()
        session?.renderer?.let(::closeLater)
        val sessionPlayer = if (wasHandedOff) into else out
        if (sessionPlayer != null && target != null && (settle || (!wasHandedOff && tookOver))) {
            sessionPlayer.seekTo(target)
        }
    }

    private fun loopFallBack(reason: String) {
        Log.d(TAG, "loop mix falls back to a blend: $reason")
        loopPlanned = null
        loopRefusal = reason
        loopBail(reason, settle = false)
    }

    private fun closeLater(renderer: LoopMixRenderer) {
        scope.launch {
            delay(LOOP_RENDERER_CLOSE_DELAY_MS)
            renderer.close()
        }
    }

    private var lastSmartPlan: TransitionPlan? = null
    private var lastSmartPlanStartMs = 0L

    private fun publishAutomixDetail() {
        val player = active()
        val item = player.currentMediaItem
        val text = when {
            !AppSettings.smartFadeEnabled.value || item == null || ListenTogether.state.value.inParty -> null
            else -> describeAutomix(player, item)
        }
        if (AppSettings.automixDetail.value != text) AppSettings.automixDetail.value = text
    }

    private fun describeAutomix(player: ExoPlayer, item: MediaItem): String {
        val session = loopSession
        if (session != null) {
            val m = session.mixSeconds(System.nanoTime(), outgoing?.isPlaying ?: false)
            val stage = when {
                phase == Phase.LOOP_ARMING || m == null || m < 0 ->
                    "loop mix in ${ceil(-(m ?: -LOOP_ARM_LEAD_MS / 1000.0)).toInt().coerceAtLeast(0)} s"
                m < session.clock.releaseAt -> "looping"
                else -> "letting go"
            }
            val aligned = session.alignedErrorMs.takeIf { it.isFinite() }
                ?.let { " · handover ${"%+.1f".format(Locale.ROOT, it)} ms" }.orEmpty()
            return "Automix · $stage · ${describeLoop(session.plan)}$aligned"
        }
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val planned = loopPlanned
        if (planned != null && next != null && loopPlanKey?.startsWith("${item.mediaId}->${next.mediaId}|") == true) {
            val inSeconds = (planned.out.loopStart - player.currentPosition / 1000.0).coerceAtLeast(0.0)
            return "Automix · loop mix in ${ceil(inSeconds).toInt()} s · ${describeLoop(planned)}"
        }
        val plan = lastSmartPlan
        if (plan != null && !plan.blocked && next != null && AppSettings.smartTransitionWindow.value != null) {
            val inSeconds = ((lastSmartPlanStartMs - player.currentPosition) / 1000.0).coerceAtLeast(0.0)
            val whenText = if (inSeconds > 0) "in ${ceil(inSeconds).toInt()} s" else "now"
            val bars = if (plan.beatSeconds > 0) (plan.fadeSeconds / (4 * plan.beatSeconds)).roundToInt() else 0
            val length = if (bars > 0) "$bars bars" else "${"%.1f".format(Locale.ROOT, plan.fadeSeconds)} s"
            val why = plan.policyReasons.firstOrNull()?.let { " · $it" }.orEmpty()
            val noLoop = loopRefusal?.let { " · no loop: $it" }.orEmpty()
            return when (plan.transitionStyle) {
                TransitionStyle.EQUAL_POWER -> "Automix · crossfading $whenText · ${"%.0f".format(Locale.ROOT, plan.fadeSeconds)} s$why$noLoop"
                TransitionStyle.DJ_FILTER -> "Automix · filter mix $whenText · $length$why$noLoop"
                else -> {
                    val rate = plan.incomingPlaybackRate
                    val stretch = if (abs(rate - 1) < 0.0005) "same tempo" else "next at ${"%.3f".format(Locale.ROOT, rate)}×"
                    "Automix · mixing $whenText · $length · $stretch$noLoop"
                }
            }
        }
        fun label(target: MediaItem?, outgoing: Boolean): String {
            if (target == null) return "none"
            val analysis = analysisFor(target)
            val state = stateOf(target, analysis)
            if (state == TrackAnalysisState.ANALYSING) return "analysing…"
            if (state == TrackAnalysisState.WAITING) return "waiting"
            if (state == TrackAnalysisState.FAILED || !analysis.isUsable) return "failed"
            val side = if (outgoing) analysis.tailBeats else analysis.headBeats
            val bpm = side?.bpm ?: analysis.bpm
            val confidence = side?.confidence ?: analysis.beatConfidence
            val key = analysis.key.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val grid = if (side != null) "model grid" else "dsp grid"
            val refining = if (state == TrackAnalysisState.REFINING) ", refining" else ""
            return "analysed (${bpm.roundToInt()} BPM$key, $grid ${"%.2f".format(Locale.ROOT, confidence)}$refining)"
        }
        return "Automix · this song: ${label(item, true)} · next: ${label(next, false)}"
    }

    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopBail("interrupted", settle = false)
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
        render = Render()
        nudgeUntil = 0L

        if (handedOff) {
            incoming?.let {
                it.volume = 1f
                beginEase(it, incomingPlaybackRate, incomingAnalysis)
            }
            outgoing?.let(::retire)
        } else {
            outgoing?.let { out ->
                out.volume = 1f
                if (outgoingPlaybackRate != 1.0) {
                    beginEase(out, outgoingPlaybackRate, out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis())
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
        rampSteps = 0
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

    private val tracks = object : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            if (!aimIncoming) return
            if (handedOff) filters.incoming(lowPassHz, highPassHz) else filters.outgoing(lowPassHz, highPassHz)
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            if (!aimOutgoing) return
            if (handedOff) filters.outgoing(lowPassHz, highPassHz) else filters.incoming(lowPassHz, highPassHz)
        }

        override fun incomingLeadMs(): Long =
            if (handedOff) filters.incomingLeadMs() else filters.outgoingLeadMs()

        override fun outgoingLeadMs(): Long =
            if (handedOff) filters.outgoingLeadMs() else filters.incomingLeadMs()

        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            if (!aimOutgoing) return
            if (handedOff) {
                filters.outgoingEcho(delaySeconds, send, dry)
            } else {
                filters.incomingEcho(delaySeconds, send, dry)
            }
        }
    }

    private var aimIncoming = true
    private var aimOutgoing = true

    private fun rideFilters(incomingAt: Float, outgoingAt: Float?) {
        if (outgoingAt == incomingAt) {
            rideStyle(incomingAt)
            return
        }
        aimOutgoing = false
        rideStyle(incomingAt)
        aimOutgoing = true
        if (outgoingAt == null) return
        aimIncoming = false
        rideStyle(outgoingAt)
        aimIncoming = true
    }

    private fun rideStyle(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> when (render.filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> rideFallbackBassHandoff(progress)
                FilterTransitionVariant.SWEEP,
                FilterTransitionVariant.ECHO_RIDE,
                -> rideFilterSweep(progress)
            }
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            TransitionStyle.GAPLESS -> filters.open()
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
        if (render.echo) rideEchoOut(progress)
    }

    private fun rideEchoOut(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.echoAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        if (p < render.echoFrom) return
        val send = if (p >= render.echoAt) 0.0 else (p - render.echoFrom) / (render.echoAt - render.echoFrom)
        tracks.outgoingEcho(render.echoSecondsF, send.toFloat(), echoDry(progress))
    }

    private fun echoDry(progress: Float): Float =
        (1.0 - ((progress - render.echoAt) / render.echoKill).coerceIn(0.0, 1.0)).toFloat()

    private fun outgoingLevel(progress: Float, wallSpan: Float): Float {
        if (!render.echo) return mixFall(progress)
        val sinceEcho = (progress - render.echoAt.toFloat()) * wallSpan
        val repeats = if (sinceEcho > 0f) sinceEcho / (render.echoSecondsF * 1000f) else 0f
        val wet = if (sinceEcho > 0f) ECHO_LEVEL * ECHO_DECAY.pow(repeats) else 0f
        return maxOf(echoDry(progress), wet)
    }

    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        tracks.outgoing(
            glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
            TransitionFilterProcessor.OFF_HZ,
        )
        tracks.incoming(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val entryCorner = entryHighPass(
            progress,
            sweep,
            glide(ENTRY_HIGH_PASS_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, render.clash),
            render.vocalEntryOpenBy,
        )
        val handover = ((progress - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, maxOf(entryCorner, bassCutoff(1.0 - handover)))
    }

    private fun rideFallbackBassHandoff(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        val exitAmount = ((p - render.filterSwapAt) / (1.0 - render.filterSwapAt)).coerceIn(0.0, 1.0)
        val handoffTone = glide(
            TransitionFilterProcessor.OPEN_HZ.toDouble(),
            FALLBACK_HANDOFF_FLOOR_HZ,
            exitAmount.pow(FALLBACK_HANDOFF_SHAPE),
        )
        val vocalTone = glide(
            TransitionFilterProcessor.OPEN_HZ.toDouble(),
            VOCAL_SEPARATION_FLOOR_HZ,
            render.clash * p.pow(VOCAL_SAFE_EXIT_SHAPE),
        )
        val outgoingTone = minOf(handoffTone, vocalTone).toFloat()
        val incomingEntry = entryHighPass(
            progress,
            1.0,
            glide(FALLBACK_HANDOFF_ENTRY_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, render.clash),
            render.vocalEntryOpenBy,
        )
        tracks.outgoing(outgoingTone, bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, maxOf(incomingEntry, bassCutoff(1.0 - handover)))
    }

    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun rideBassSwap(progress: Float) {
        val handover = ((progress - render.swapFrom) / render.swapWidth).coerceIn(0.0, 1.0)
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        val entry = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                render.blendEntryOpenBy,
            ),
        )
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, entry)
        tracks.outgoing(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = render.blendExitFrom
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    private fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    private fun isRealMix(): Boolean = smartFadeActive && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0 ||
            outgoingPlaybackRate != 1.0
        )

    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun mixRise(progress: Float): Float {
        if (!render.advanced) return riseGain(progress)
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) held(riseGain(progress), riseGain(progress / render.swapAtF)) else riseGain(progress)
            TransitionStyle.DJ_FILTER -> riseGain(progress.pow(FILTER_RISE_SHAPE))
            else -> riseGain(progress)
        }
        val vocalSafe = riseGain(progress.pow(VOCAL_SAFE_RISE_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    private fun mixFall(progress: Float): Float {
        if (!render.advanced) return fallGain(progress)
        if (render.echo) return 1f
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND -> {
                if (!render.bassSwap) {
                    fallGain(progress)
                } else {
                    val from = render.fallFrom
                    held(fallGain(progress), if (progress <= from) 1f else fallGain((progress - from) / (1f - from)))
                }
            }
            TransitionStyle.DJ_FILTER -> fallGain(progress.pow(FILTER_FALL_SHAPE))
            else -> fallGain(progress)
        }
        val vocalSafe = fallGain(progress.pow(VOCAL_SAFE_FALL_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    private fun held(plain: Float, dj: Float): Float = plain + (dj - plain) * render.holdFaders

    private fun holdPhase(out: ExoPlayer, into: ExoPlayer, progress: Float) {
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
        val factor = if (median > 0) {
            1f - NUDGE
        } else {
            minOf(1f + NUDGE, (MAX_TOTAL_SPEED_UP / incomingPlaybackRate).toFloat())
        }
        if (factor <= 1f && median < 0) return
        into.setPlaybackSpeed(base * factor)
        nudgeUntil = now + (abs(median) / (base * abs(factor - 1f)) * 1000).roundToLong().coerceIn(1L, MAX_NUDGE_MS)
    }

    private fun incomingBaseSpeed(): Float = (AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat()

    private fun phaseError(out: ExoPlayer, into: ExoPlayer): Double {
        if (!beatAt(outgoingAnalysis, positionMs(out) / 1000.0)) return Double.NaN
        val outSince = beatScratch[0]
        val outBeat = beatScratch[1]
        if (!beatAt(incomingAnalysis, positionMs(into) / 1000.0)) return Double.NaN
        val rate = mediaRatio
        val period = minOf(outBeat, beatScratch[1] / rate)
        var diff = (beatScratch[0] / rate - outSince) % period
        if (diff > period / 2) diff -= period else if (diff < -period / 2) diff += period
        if (abs(diff) > period * LOCK_MAX_BEATS) return Double.NaN
        return diff * rate
    }

    private fun beatAt(analysis: TrackAnalysis, positionSec: Double): Boolean {
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

    private fun beginEase(player: ExoPlayer, rate: Double, analysis: TrackAnalysis) {
        val stretch = rate - 1.0
        if (abs(stretch) < EASE_DONE) {
            player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            return
        }
        val beat = when {
            analysis.beatInterval > 0.0 -> analysis.beatInterval
            analysis.bpm > 0.0 -> 60.0 / analysis.bpm
            else -> 0.0
        }
        val steps = rampStepsFor(rate)
        easeStep = stretch / steps
        easeRate = rate
        easeStepMs = if (beat > 0.0) (beat * 1000).roundToLong() else EASE_FALLBACK_STEP_MS
        easeItemIndex = player.currentMediaItemIndex
        easeNextAtMs = player.currentPosition + easeStepMs
        easing = player
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * rate).toFloat())
    }

    private fun driveEase() {
        val player = easing ?: return
        val position = player.currentPosition
        if (player !== active() ||
            player.currentMediaItemIndex != easeItemIndex ||
            position < easeNextAtMs - 2 * easeStepMs ||
            ListenTogether.state.value.inParty
        ) {
            endEase()
            return
        }
        if (position < easeNextAtMs) return
        easeRate -= easeStep
        if (abs(easeRate - 1.0) < EASE_DONE) {
            endEase(clickless = false)
            return
        }
        player.setPlaybackSpeed((AppSettings.playbackSpeed.value * easeRate).toFloat())
        easeNextAtMs += easeStepMs
    }

    private fun rampOutgoing(out: ExoPlayer) {
        val due = ((out.currentPosition - rampStartMs) / rampStepMs + 1).toInt().coerceIn(0, rampSteps)
        if (due <= rampStep) return
        rampStep = due
        val rate = 1.0 + (outgoingPlaybackRate - 1.0) * due / rampSteps
        out.setPlaybackSpeed((AppSettings.playbackSpeed.value * rate).toFloat())
    }

    private fun rampStepsFor(rate: Double): Int {
        val stretch = abs(rate - 1.0)
        return if (stretch < EASE_DONE) 0 else ceil(stretch / TEMPO_STEP_PER_BEAT).toInt()
    }

    private fun endEase(clickless: Boolean = true) {
        val player = easing ?: return
        easing = null
        if (clickless) {
            setPlaybackSpeedClickless(player, AppSettings.playbackSpeed.value)
        } else {
            softSpeedJob?.cancel()
            softSpeedJob = null
            player.volume = 1f
            player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        }
    }

    private fun setPlaybackSpeedClickless(player: ExoPlayer, target: Float) {
        if (abs(player.playbackParameters.speed - target) < SPEED_CHANGE_EPSILON) return
        if (!player.isPlaying || player !== active() || player.volume <= 0f) {
            player.setPlaybackSpeed(target)
            return
        }
        softSpeedJob?.cancel()
        softSpeedJob = scope.launch {
            val level = player.volume
            try {
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * (1f - step.toFloat() / SPEED_NOTCH_STEPS)
                    delay(SPEED_NOTCH_STEP_MS)
                }
                player.setPlaybackSpeed(target)
                delay(SPEED_NOTCH_HOLD_MS)
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * step.toFloat() / SPEED_NOTCH_STEPS
                    delay(SPEED_NOTCH_STEP_MS)
                }
            } finally {
                if (player === active() && phase == Phase.IDLE) player.volume = level
            }
        }
    }

    private fun headroomFor(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain + outgoingGain
        return if (sum <= 1f) 1f else 1f / kotlin.math.sqrt(sum)
    }

    private companion object {
        const val MIX_PREFETCH_MS = 30_000L

        const val PAUSED_MARKER_STEP_MS = 1_000L

        const val MIX_VIEW_FADER_POINTS = 64

        const val MIX_PREVIEW_STALE_MS = 1_500L

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

        const val FILTER_ENTRY_HZ = 7_000.0

        const val FILTER_FLOOR_HZ = 300.0

        const val BASS_SWAP_HZ = 200.0

        const val BASS_SWAP_WIDTH = 0.10

        const val SWAP_BEATS = 0.5

        const val FILTER_BASS_SWAP_AT = 0.5

        const val FILTER_FALL_SHAPE = 1.6f
        const val FILTER_RISE_SHAPE = 0.8f

        const val FALLBACK_HANDOFF_ENTRY_HZ = 900.0
        const val FALLBACK_HANDOFF_FLOOR_HZ = 1_600.0
        const val FALLBACK_HANDOFF_SHAPE = 0.8

        const val VOCAL_SAFE_ENTRY_HIGH_PASS_HZ = 2_400.0
        const val VOCAL_SAFE_ENTRY_OPEN_BY = 0.78
        const val VOCAL_SAFE_EXIT_SHAPE = 0.55
        const val VOCAL_SAFE_RISE_SHAPE = 1.65f
        const val VOCAL_SAFE_FALL_SHAPE = 0.65f

        const val ECHO_KILL_BEATS = 4.0

        const val ECHO_LEVEL = 0.5f
        const val ECHO_DECAY = 0.4f

        const val LOCK_START_MS = 300L

        const val LOCK_SAMPLES = 5

        const val LOCK_DEADBAND_SECONDS = 0.008

        const val LOCK_SETTLE_MS = 400L

        const val LOCK_IN_PHASE_MS = 500L

        const val NUDGE = 0.03f

        const val MAX_NUDGE_MS = 1_500L

        const val LOCK_UNTIL = 0.9f

        const val LOCK_MAX_BEATS = 0.3

        const val TEMPO_STEP_PER_BEAT = 0.0075

        const val MAX_TOTAL_SPEED_UP = 1.12

        const val EASE_DONE = 1e-4

        const val EASE_FALLBACK_STEP_MS = 500L

        const val SPEED_NOTCH_STEPS = 2
        const val SPEED_NOTCH_STEP_MS = 2L
        const val SPEED_NOTCH_HOLD_MS = 2L
        const val SPEED_CHANGE_EPSILON = 0.0001f

        const val FILTER_SWEEP_SHAPE = 0.75

        const val ENTRY_HIGH_PASS_HZ = 1_200.0

        const val ENTRY_OPEN_BY = 0.6

        const val ENTRY_SHAPE = 0.35

        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0

        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0

        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0

        const val BLEND_ENTRY_OPEN_BY = 0.45

        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7

        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

        const val BLEND_EXIT_FROM = 0.3

        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        const val HANDOFF_AT = 0.5f

        const val HANDOFF_GUARD_MS = 400L

        const val ANCHOR_TOLERANCE_NANOS = 25_000_000L

        const val TEMPO_TOLERANCE = 0.005f

        const val IDLE_STEP_MS = 250L

        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L

        const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L

        const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
        const val BAIL_STEP_MS = 15L

        const val LOOP_STEP_MS = 20L

        const val LOOP_SETUP_SECONDS = 15.0
        const val LOOP_ARM_LEAD_MS = 12_000L
        const val LOOP_MIN_LEAD_MS = 9_000L
        const val LOOP_BUILD_TIMEOUT_MS = 8_000L

        const val LOOP_ALIGN_SETTLE_SECONDS = 1.2
        const val LOOP_ALIGN_ROUNDS = 3
        const val LOOP_ALIGN_SAMPLES = 15
        const val LOOP_ALIGN_SAMPLE_MS = 30L

        const val LOOP_END_MARGIN_SECONDS = 0.3

        const val LOOP_WRAP_MS = 400L
        const val LOOP_ANNOUNCE_MS = 2_000L

        const val LOOP_RENDERER_CLOSE_DELAY_MS = 1_500L
    }
}
