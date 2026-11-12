package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.bxane.nuvx.playback.audio.DspChain
import com.bxane.nuvx.playback.audio.PrecisionAudioSink
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectAudioSink
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectRoutingSink

@UnstableApi
internal class PlaybackRenderersFactory(
    context: Context,
    private val outputRouter: PlaybackOutputRouter,
    private val activeFilter: () -> TransitionFilterProcessor,
    private val spatial: SpatialAudioProcessor,
    private val equalizer: EqualizerProcessor,
    private val transition: TransitionFilterProcessor,
    private val loudness: LoudnessProcessor,
) : FfmpegFallbackRenderersFactory(context) {
    init {
        setEnableAudioFloatOutput(outputRouter.configuredFloatOutput)
        setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                MediaCodecSelector.PREFER_SOFTWARE
            } else {
                MediaCodecSelector.DEFAULT
            }
            val candidates = selector.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (mimeType == MimeTypes.AUDIO_FLAC) {
                candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
            } else {
                candidates
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val defaultSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(
                enableAudioTrackPlaybackParams || enableFloatOutput,
            )
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    emptyArray<AudioProcessor>(),
                    SilenceSkippingAudioProcessor(
                        MIN_SILENCE_US,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                        SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                        SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                    ),
                    SonicAudioProcessor(),
                ),
            )
            .build()

        val dspChain = DspChain(spatial, equalizer, transition, loudness)
        val precision = PrecisionAudioSink(
            delegate = defaultSink,
            dspChain = dspChain,
            enableFloatOutput = enableFloatOutput,
            preferredOutputEncodingProvider = { format -> outputRouter.resolvePreferredOutputEncoding(format) },
            isAudible = { transition === activeFilter() },
        )
        return BitPerfectRoutingSink(precision, BitPerfectAudioSink(BitPerfectOutput.forContext(context)))
    }

    private companion object {
        const val MIN_SILENCE_US = 1_000_000L
    }
}
