package com.bxane.nuvx.data.sources

import android.media.MediaCodecList
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import java.util.Locale

object DeviceCodecs {

    private const val TAG = "nuvx"

    private val DOLBY_MIMES = listOf("audio/eac3-joc", "audio/eac3")

    @Volatile
    private var probed: Boolean? = null

    @Volatile
    internal var forced: Boolean? = null

    val playsDolbyAtmos: Boolean
        get() = forced ?: probed ?: probe().also { probed = it }

    val atmosWanted: Boolean
        get() = playsDolbyAtmos && AppSettings.dolbyAtmos.value && !BitPerfectOutput.ready.value

    fun atmosRefusal(): String = when {
        !playsDolbyAtmos -> "which this device has no decoder for"
        BitPerfectOutput.ready.value -> "which bit-perfect output cannot carry"
        else -> "which is switched off in Settings"
    }

    private fun probe(): Boolean {
        val viaMedia3 = media3Decoders()
        if (viaMedia3 != null) {
            TrackLog.d(TAG, "Dolby Atmos decoders (Media3): ${viaMedia3.ifEmpty { "none" }}")
            return viaMedia3.isNotEmpty()
        }
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.lowercase(Locale.ROOT) in DOLBY_MIMES
                }
            }.map { it.name }
        }.onSuccess {
            TrackLog.d(TAG, "Dolby Atmos decoders (platform): ${it.ifEmpty { "none" }}")
        }.map {
            it.isNotEmpty()
        }.getOrElse {
            TrackLog.w(TAG, "could not read the codec list; assuming Dolby Atmos plays — ${it.message}")
            true
        }
    }

    @UnstableApi
    private fun media3Decoders(): List<String>? = runCatching {
        DOLBY_MIMES.flatMap { MediaCodecUtil.getDecoderInfos(it, false, false) }
            .map { it.name }
            .distinct()
    }.getOrElse {
        TrackLog.w(TAG, "Media3 could not enumerate Dolby decoders — ${it.message}")
        null
    }
}
