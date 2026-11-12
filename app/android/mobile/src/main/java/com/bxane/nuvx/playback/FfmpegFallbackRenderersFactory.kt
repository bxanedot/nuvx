package com.bxane.nuvx.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal open class FfmpegFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            withoutPlatformAlac(mediaCodecSelector),
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

private fun withoutPlatformAlac(selector: MediaCodecSelector) =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (mimeType == MimeTypes.AUDIO_ALAC && ffmpegDecodesAlac) {
            emptyList()
        } else {
            selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }
    }

private val ffmpegDecodesAlac by lazy { FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC) }
