package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.MixDeck
import com.bxane.nuvx.data.settings.MixTransitionView
import com.bxane.nuvx.playback.smart.FilterTransitionVariant
import com.bxane.nuvx.playback.smart.TrackAnalysis
import com.bxane.nuvx.playback.smart.TransitionPlan
import com.bxane.nuvx.playback.smart.TransitionStyle
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class CrossfadeMixViewPublisher(
    private val waves: CrossfadeWaveforms,
    private val preview: CrossfadePreviewController,
    private val envelope: CrossfadeEnvelope,
    private val currentRender: () -> CrossfadeRender,
    private val fadeEndTimeMs: () -> Long,
    private val configuredFadeMs: () -> Long,
    private val currentMediaRatio: () -> Double,
    private val incomingCueMs: () -> Long,
    private val currentPlan: () -> TransitionPlan?,
    private val outgoingTrackAnalysis: () -> TrackAnalysis,
    private val incomingTrackAnalysis: () -> TrackAnalysis,
) {
    fun publish(out: ExoPlayer, into: ExoPlayer) {
        val plan = currentPlan() ?: return
        val outItem = out.currentMediaItem ?: return
        val inItem = into.currentMediaItem ?: return
        val render = currentRender()
        val outgoingAnalysis = outgoingTrackAnalysis()
        val incomingAnalysis = incomingTrackAnalysis()
        val fadeEndMs = fadeEndTimeMs()
        val fadeMs = configuredFadeMs()
        val mediaRatio = currentMediaRatio()
        val incomingCueTimeMs = incomingCueMs()
        val geometry = CrossfadeGeometry.playback(
            fadeEndMs,
            fadeMs,
            render.advanced,
            mediaRatio,
            incomingCueTimeMs,
        )
        val outStart = geometry.outStart
        val outEnd = geometry.outEnd
        val outSpan = geometry.outSpan
        val beatSeconds = plan.beatSeconds
        val beats = if (beatSeconds > 0) (outSpan / beatSeconds).roundToInt() else 0
        val marks = CrossfadeGeometry.beatMarks(outgoingAnalysis, outStart, outEnd, beatSeconds)
        val wallSpan = (fadeMs * if (render.advanced) mediaRatio else 1.0).toFloat()
        val samples = CrossfadeGeometry.MIX_VIEW_FADER_POINTS
        val fadeOut = FloatArray(samples) { envelope.outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
        val fadeIn = FloatArray(samples) { envelope.mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }

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
        waves.mixViewIds = outItem.mediaId to inItem.mediaId
        AppSettings.smartMixPreview.value = null
        waves.mixPreviewIds = null
        preview.resetKey()
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
        waves.republishWaves()
    }
}
