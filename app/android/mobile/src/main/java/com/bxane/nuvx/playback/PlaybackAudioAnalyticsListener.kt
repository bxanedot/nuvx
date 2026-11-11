package com.bxane.nuvx.playback

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import java.util.Locale

@UnstableApi
internal class PlaybackAudioAnalyticsListener(
    private val onDolbyAtmosChanged: (Boolean) -> Unit,
    private val applySpatialAudioEnabled: () -> Unit,
    private val publishNerdStats: () -> Unit,
    private val outputRouter: PlaybackOutputRouter,
) : AnalyticsListener {
    var audioFormatFor: String? = null

    private var swapCutAt: Long? = null

    fun markSwapCut() {
        swapCutAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        audioFormatFor = eventTime.mediaId()
        val measured = format.measure()
        val khz = measured.sampleRateHz
            ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
        val kbps = format.bitrate.takeIf { it > 0 }
            ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
        val depth = measured.bitDepth?.let { "${it}-bit" } ?: "?-bit"
        val channels = measured.channels?.let { "${it}ch" } ?: "?ch"
        TrackLog.i(
            "DECODE",
            "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth $channels",
            about = audioFormatFor,
        )
        onDolbyAtmosChanged(NerdStats.isDolbyAtmosMime(format.sampleMimeType))
        applySpatialAudioEnabled()
        publishNerdStats()
        outputRouter.currentAudioInputFormat = format
        outputRouter.applyOutputRoute()
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        val cutAt = swapCutAt ?: return
        swapCutAt = null
        TrackLog.d(
            "nuvx",
            "swap seam: ${android.os.SystemClock.elapsedRealtime() - cutAt}ms of silence",
            about = eventTime.mediaId(),
        )
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val cutAt = swapCutAt ?: return
        if (state == Player.STATE_READY) {
            TrackLog.d(
                "nuvx",
                "swap leg: ready ${android.os.SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        AudioOutputStatus.publishDecoder(decoderName)
        TrackLog.i(
            "AUDIO_OUT",
            "decoder=$decoderName initialized in ${initializationDurationMs}ms",
            about = eventTime.mediaId(),
        )
        val cutAt = swapCutAt ?: return
        TrackLog.d(
            "nuvx",
            "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                "${android.os.SystemClock.elapsedRealtime() - cutAt}ms after the cut",
            about = eventTime.mediaId(),
        )
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        AudioOutputStatus.publishAudioTrack(
            encoding = audioTrackConfig.encoding,
            sampleRateHz = audioTrackConfig.sampleRate,
            bufferSize = audioTrackConfig.bufferSize,
        )
        TrackLog.i(
            "AUDIO_OUT",
            "AudioTrack ${audioTrackConfig.sampleRate}Hz encoding=${audioTrackConfig.encoding}",
            about = eventTime.mediaId(),
        )
    }

    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId
}
