package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.playback.loopmix.LoopMixSession
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class CrossfadeAutomixDetailController(
    private val active: () -> ExoPlayer,
    private val loopSession: () -> LoopMixSession?,
    private val outgoing: () -> ExoPlayer?,
    private val loopArming: () -> Boolean,
    private val loopPlanner: CrossfadeLoopPlanner,
    private val lastSmartPlan: () -> TransitionPlan?,
    private val lastSmartPlanStartMs: () -> Long,
    private val analysisFor: (MediaItem) -> TrackAnalysis,
    private val stateOf: (MediaItem, TrackAnalysis) -> TrackAnalysisState,
) {
    fun publish() {
        val player = active()
        val item = player.currentMediaItem
        val text = when {
            !AppSettings.smartFadeEnabled.value || item == null || ListenTogether.state.value.inParty -> null
            else -> describe(player, item)
        }
        if (AppSettings.automixDetail.value != text) AppSettings.automixDetail.value = text
    }

    private fun describe(player: ExoPlayer, item: MediaItem): String {
        val session = loopSession()
        if (session != null) {
            val m = session.mixSeconds(System.nanoTime(), outgoing()?.isPlaying ?: false)
            val stage = when {
                loopArming() || m == null || m < 0 ->
                    "loop mix in ${ceil(-(m ?: -LOOP_ARM_LEAD_MS / 1000.0)).toInt().coerceAtLeast(0)} s"
                m < session.clock.releaseAt -> "looping"
                else -> "letting go"
            }
            val aligned = session.alignedErrorMs.takeIf { it.isFinite() }
                ?.let { " · handover ${"%+.1f".format(Locale.ROOT, it)} ms" }.orEmpty()
            return "Automix · $stage · ${loopPlanner.describeLoop(session.plan)}$aligned"
        }
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val planned = loopPlanner.planned
        if (planned != null && next != null && loopPlanner.isPlannedFor(item.mediaId, next.mediaId)) {
            val inSeconds = (planned.out.loopStart - player.currentPosition / 1000.0).coerceAtLeast(0.0)
            return "Automix · loop mix in ${ceil(inSeconds).toInt()} s · ${loopPlanner.describeLoop(planned)}"
        }
        val plan = lastSmartPlan()
        if (plan != null && !plan.blocked && next != null && AppSettings.smartTransitionWindow.value != null) {
            val inSeconds = ((lastSmartPlanStartMs() - player.currentPosition) / 1000.0).coerceAtLeast(0.0)
            val whenText = if (inSeconds > 0) "in ${ceil(inSeconds).toInt()} s" else "now"
            val bars = if (plan.beatSeconds > 0) (plan.fadeSeconds / (4 * plan.beatSeconds)).roundToInt() else 0
            val length = if (bars > 0) "$bars bars" else "${"%.1f".format(Locale.ROOT, plan.fadeSeconds)} s"
            val why = plan.policyReasons.firstOrNull()?.let { " · $it" }.orEmpty()
            val noLoop = loopPlanner.refusal?.let { " · no loop: $it" }.orEmpty()
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

    private companion object {
        const val LOOP_ARM_LEAD_MS = 12_000L
    }
}
