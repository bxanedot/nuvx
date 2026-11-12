package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

internal class PlaybackLoudnessController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val activeLoudness: () -> LoudnessProcessor,
) {
    private var retryJob: Job? = null

    fun gainFor(mediaId: String): Float = gainMb(mediaId)
        ?.let { 10.0.pow(it / 2000.0).toFloat() }
        ?: 1f

    fun setup(mediaId: String?) {
        val exoPlayer = player() ?: return
        activeLoudness().track(mediaId, nextMediaIdOf(exoPlayer))
        publish(mediaId)
    }

    fun nextMediaIdOf(exoPlayer: ExoPlayer): String? {
        val next = exoPlayer.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next !in 0 until exoPlayer.mediaItemCount) return null
        return exoPlayer.getMediaItemAt(next).mediaId
    }

    fun scheduleRetry(mediaId: String?) {
        retryJob?.cancel()
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        if (StreamResolver.loudnessDbFor(id) != null) return
        retryJob = scope.launch {
            delay(LOUDNESS_RETRY_MS)
            if (player()?.currentMediaItem?.mediaId == id) publish(id)
        }
    }

    fun cancelRetry() {
        retryJob?.cancel()
    }

    private fun gainMb(mediaId: String?): Int? {
        if (!AppSettings.loudnessNormalization.value) return null
        if (AppSettings.loudnessOffOnSpeaker.value &&
            AudioOutputStatus.current.value.routeKind == AudioRouting.Kind.PHONE
        ) return null
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val loudnessDb = StreamResolver.loudnessDbFor(id) ?: return null
        return (-loudnessDb * 100.0).roundToInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
    }

    private fun publish(mediaId: String?) {
        val gainMb = gainMb(mediaId)
        val lufs = mediaId?.takeIf { it.isNotBlank() }?.let(StreamResolver::loudnessDbFor)
        if (gainMb == null || lufs == null) {
            AudioOutputStatus.publishLoudness(gainDb = null, lufs = null)
        } else {
            AudioOutputStatus.publishLoudness(gainDb = gainMb / 100f, lufs = lufs.toFloat())
        }
    }

    private companion object {
        const val MIN_GAIN_MB = -1500
        const val MAX_GAIN_MB = 300
        const val LOUDNESS_RETRY_MS = 6_000L
    }
}
