package com.bxane.nuvx.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.data.settings.TransitionWindow
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.playback.smart.CrossfadeMode
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.planTransition
import java.util.Locale
import kotlin.math.roundToLong

internal data class CrossfadeTransitionStart(
    val fade: Long,
    val endMs: Long,
    val smart: Boolean,
    val cueTimeMs: Long = 0L,
    val playbackRate: Double = 1.0,
    val outgoingRate: Double = 1.0,
    val beatMs: Long = 0L,
    val renderStyle: CrossfadeRender = CrossfadeRender(),
    val plan: TransitionPlan? = null,
)

@UnstableApi
internal class CrossfadeTransitionPlanner(
    private val active: () -> ExoPlayer,
    private val analysisFor: (MediaItem) -> TrackAnalysis,
    private val requestAnalysis: (MediaItem, Long) -> Unit,
    private val versionSwapActive: () -> Boolean,
    private val nextStreamReady: (MediaItem) -> Boolean,
    private val isIdle: () -> Boolean,
    private val configuredFadeMs: () -> Long,
    private val renderFor: (TransitionPlan, Boolean) -> CrossfadeRender,
    private val begin: (CrossfadeTransitionStart) -> Unit,
    private val trackInfoResolver: CrossfadeTrackInfoResolver,
    private val readAhead: CrossfadeReadAheadController,
    private val sleepFade: CrossfadeSleepFadeController,
    private val speedController: CrossfadeSpeedController,
    private val mixPresentation: CrossfadeMixPresentationController,
    private val loopPlanner: CrossfadeLoopPlanner,
    private val loopMix: CrossfadeLoopMixController,
    private val tag: String,
) {
    private var pausedMarkerAt = 0L
    private var plannedTransition: Pair<String, Long>? = null
    private var lastPlanVerdict = ""
    private var lastMarkerVerdict = ""
    private var smartPlan: TransitionPlan? = null
    private var smartPlanStartMs = 0L

    val lastSmartPlan: TransitionPlan? get() = smartPlan
    val lastSmartPlanStartMs: Long get() = smartPlanStartMs

    fun considerAutoTransition() {
        val player = active()
        if (!player.isPlaying) {
            refreshMarkerWhilePaused(player)
            return
        }
        readAhead.clearHoldWanted()
        planAutoTransition(player)
        if (!readAhead.holdWanted && isIdle()) readAhead.releaseHold()
    }

    fun plannedTransitionFor(mediaId: String): Long? =
        plannedTransition?.takeIf { it.first == mediaId }?.second

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
        readAhead.holdAhead(player, duration)
        if (remaining > fade + ARM_LEAD_MS) return

        begin(CrossfadeTransitionStart(fade = fade, endMs = duration, smart = false))
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
                tag,
                "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        smartPlan = plan
        smartPlanStartMs = (plan.transitionStart * 1000).roundToLong()
        mixPresentation.publishPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, plan, advanced)

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
            readAhead.holdAhead(player, duration)
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
                tag,
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
            mixPresentation.prefetch(
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
        readAhead.holdAhead(player, duration)
        val beatMs = (plan.beatSeconds * 1000).roundToLong()
        val rampMs = if (beatMs > 0L) speedController.rampStepsFor(plan.outgoingPlaybackRate) * beatMs else 0L
        if (remaining > ARM_LEAD_MS + rampMs) return

        begin(
            CrossfadeTransitionStart(
                fade = fade,
                endMs = (plan.transitionEnd * 1000).roundToLong(),
                smart = true,
                cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
                playbackRate = plan.incomingPlaybackRate,
                outgoingRate = plan.outgoingPlaybackRate,
                beatMs = beatMs,
                renderStyle = renderFor(plan, advanced),
                plan = plan,
            ),
        )
    }

    private fun requestAnalysisAround(player: ExoPlayer, duration: Long) {
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return
        requestAnalysis(currentItem, duration)
        requestAnalysis(nextItem, trackInfoResolver.nextItemDurationMs(nextIndex, nextItem))
    }

    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    private companion object {
        const val MIX_PREFETCH_MS = 30_000L
        const val PAUSED_MARKER_STEP_MS = 1_000L
        const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0
        const val ARM_LEAD_MS = 4_000L
        const val LOOP_ARM_LEAD_MS = 12_000L
        const val IDLE_STEP_MS = 250L

        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )
    }
}
