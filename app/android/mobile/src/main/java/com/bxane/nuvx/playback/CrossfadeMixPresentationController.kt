package com.bxane.nuvx.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.SmartAnalysis
import com.bxane.nuvx.playback.smart.MixWaveform
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import kotlinx.coroutines.CoroutineScope

internal class CrossfadeMixPresentationController(
    scope: CoroutineScope,
    mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?,
    tag: String,
    envelope: CrossfadeEnvelope,
    currentRender: () -> CrossfadeRender,
    updateRender: (CrossfadeRender) -> Unit,
    renderFor: (TransitionPlan, Boolean) -> CrossfadeRender,
    isIdle: () -> Boolean,
    fadeEndTimeMs: () -> Long,
    configuredFadeMs: () -> Long,
    currentMediaRatio: () -> Double,
    incomingCueMs: () -> Long,
    currentPlan: () -> TransitionPlan?,
    outgoingTrackAnalysis: () -> TrackAnalysis,
    incomingTrackAnalysis: () -> TrackAnalysis,
) {
    private val waves = CrossfadeWaveforms(scope, mixWaveform, tag)

    private val preview = CrossfadePreviewController(
        waves = waves,
        envelope = envelope,
        currentRender = currentRender,
        updateRender = updateRender,
        renderFor = renderFor,
        isIdle = isIdle,
    )

    private val viewPublisher = CrossfadeMixViewPublisher(
        waves = waves,
        preview = preview,
        envelope = envelope,
        currentRender = currentRender,
        fadeEndTimeMs = fadeEndTimeMs,
        configuredFadeMs = configuredFadeMs,
        currentMediaRatio = currentMediaRatio,
        incomingCueMs = incomingCueMs,
        currentPlan = currentPlan,
        outgoingTrackAnalysis = outgoingTrackAnalysis,
        incomingTrackAnalysis = incomingTrackAnalysis,
    )

    fun prefetch(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        inDurationMs: Long,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        ratio: Double,
        wantOut: Boolean = true,
        wantIn: Boolean = true,
    ) {
        waves.prefetchMixWaves(
            outItem, inItem, outDurationMs, inDurationMs, outStart, outEnd, inStart, ratio, wantOut, wantIn,
        )
    }

    fun clear() = waves.clearMixView()

    fun publishPreview(
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
        preview.publish(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, state, plan, advanced)
    }

    fun expirePreview() = preview.expire()

    fun publishView(out: ExoPlayer, into: ExoPlayer) = viewPublisher.publish(out, into)
}
