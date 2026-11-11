package com.bxane.nuvx.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixDeck
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.playback.smart.FilterTransitionVariant
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class CrossfadePreviewController(
    private val waves: CrossfadeWaveforms,
    private val envelope: CrossfadeEnvelope,
    private val currentRender: () -> CrossfadeRender,
    private val updateRender: (CrossfadeRender) -> Unit,
    private val renderFor: (TransitionPlan, Boolean) -> CrossfadeRender,
    private val isIdle: () -> Boolean,
) {
    private var mixPreviewAt = 0L
    private var mixPreviewKey = ""

    fun publish(
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
        val geometry = CrossfadeGeometry.planned(plan)
        val outStart = geometry.outStart
        val outEnd = geometry.outEnd
        val inStart = geometry.inStart
        val ratio = geometry.ratio
        val currentAnalysing = state.current == TrackAnalysisState.WAITING || state.current == TrackAnalysisState.ANALYSING
        val nextAnalysing = state.next == TrackAnalysisState.WAITING || state.next == TrackAnalysisState.ANALYSING
        val beatSeconds = if (currentAnalysis.isUsable) plan.beatSeconds else 0.0
        val beats = if (beatSeconds > 0) (geometry.outSpan / beatSeconds).roundToInt() else 0

        waves.prefetchMixWaves(
            currentItem, nextItem, duration, nextDuration, outStart, outEnd, inStart, ratio,
            wantOut = currentAnalysis.isUsable,
            wantIn = nextAnalysis.isUsable,
        )

        val ids = currentItem.mediaId to nextItem.mediaId
        val key = "${ids.first}|${ids.second}|${round(outStart * 20)}|${round(outEnd * 20)}|${round(inStart * 20)}|" +
            "$beats|$currentAnalysing|$nextAnalysing|${currentAnalysis.bpm}|${nextAnalysis.bpm}|" +
            "${currentAnalysis.key}|${nextAnalysis.key}|${plan.transitionStyle}|${plan.bassSwap}"
        if (key == mixPreviewKey && waves.mixPreviewIds == ids && AppSettings.smartMixPreview.value != null) return
        mixPreviewKey = key

        val planned = renderFor(plan, advanced)
        val running = currentRender()
        updateRender(planned)
        val samples = CrossfadeGeometry.MIX_VIEW_FADER_POINTS
        val wallSpan = (plan.fadeMs * if (planned.advanced) ratio else 1.0).toFloat()
        val fadeOut: FloatArray
        val fadeIn: FloatArray
        try {
            fadeOut = FloatArray(samples) { envelope.outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
            fadeIn = FloatArray(samples) { envelope.mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }
        } finally {
            updateRender(running)
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

        waves.mixPreviewIds = ids
        AppSettings.smartMixPreview.value = waves.withWaves(
            MixTransitionView(
                outgoing = deck(currentItem, currentAnalysis, duration, currentAnalysing, geometry.outFrom, geometry.outTo),
                incoming = deck(nextItem, nextAnalysis, nextDuration, nextAnalysing, geometry.inFrom, geometry.inTo),
                outStart = outStart,
                outEnd = outEnd,
                inStart = geometry.inStart,
                inEnd = geometry.inEnd,
                beats = beats,
                beatMarks = CrossfadeGeometry.beatMarks(currentAnalysis, outStart, outEnd, beatSeconds),
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

    fun expire() {
        if (AppSettings.smartMixPreview.value == null) return
        val stale = isIdle() && SystemClock.elapsedRealtime() - mixPreviewAt > MIX_PREVIEW_STALE_MS
        if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null && !stale) return
        AppSettings.smartMixPreview.value = null
        waves.mixPreviewIds = null
        mixPreviewKey = ""
    }

    fun resetKey() {
        mixPreviewKey = ""
    }

    private companion object {
        const val MIX_PREVIEW_STALE_MS = 1_500L
    }
}
