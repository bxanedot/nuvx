package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.BandwidthMeter

@UnstableApi
internal class PlaybackPlayerFactory(
    private val context: Context,
    private val outputRouter: PlaybackOutputRouter,
    private val activeFilter: () -> TransitionFilterProcessor,
    private val mediaSourceFactory: () -> DefaultMediaSourceFactory,
) {
    fun build(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        filter: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            PlaybackRenderersFactory(
                context = context,
                outputRouter = outputRouter,
                activeFilter = activeFilter,
                spatial = spatial,
                equalizer = equalizer,
                transition = filter,
                loudness = loudness,
            ),
        )
        .setMediaSourceFactory(mediaSourceFactory())
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    fun buildAuditionPlayer(meter: BandwidthMeter): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(FfmpegFallbackRenderersFactory(context))
        .setMediaSourceFactory(mediaSourceFactory())
        .setBandwidthMeter(meter)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    AUDITION_BUFFER_MS,
                    AUDITION_BUFFER_MS,
                    START_PLAYBACK_MS,
                    START_PLAYBACK_MS,
                )
                .setTargetBufferBytes(AUDITION_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = false
            volume = 0f
        }

    fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            FAR_BUFFER_MS,
            START_PLAYBACK_MS,
            RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(BACK_BUFFER_MS, true)
        .build()

    private companion object {
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024
        const val BACK_BUFFER_MS = 30 * 1000
        const val START_PLAYBACK_MS = 500
        const val RESUME_PLAYBACK_MS = 2_000
        const val AUDITION_BUFFER_MS = 40_000
        const val AUDITION_BUFFER_BYTES = 24 * 1024 * 1024
    }
}
