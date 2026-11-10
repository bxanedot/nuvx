package com.bxane.nuvx.data

import com.bxane.nuvx.data.sources.SourceTrackKeys
import com.bxane.nuvx.data.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TelemetryProvenance {
    AUTHORITATIVE,
    MEASURED,
    DERIVED,
    UNKNOWN,
}

object NerdStats {

    class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int? = null,
        val claimed: StreamFormat? = null,
        val sourceName: String? = null,
        val container: String? = null,
        val bitrateProvenance: TelemetryProvenance = TelemetryProvenance.UNKNOWN,
        val pcmDataRateKbps: Int? = null,
        val mediaId: String? = null,
    ) {
        val downgraded: Boolean
            get() {
                val wantedRate = claimed?.sampleRateHz
                val wantedDepth = claimed?.bitDepth
                return (wantedRate != null && sampleRateHz != null && sampleRateHz < wantedRate) ||
                    (wantedDepth != null && bitDepth != null && bitDepth < wantedDepth)
            }

        val isLossless: Boolean
            get() = isLosslessMime(mimeType)

        val isDolbyAtmos: Boolean
            get() = isDolbyAtmosMime(mimeType)

        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

        val isHiQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) >= HI_QUALITY_KBPS

        val isMediumQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in (LOW_QUALITY_MAX_KBPS + 1) until HI_QUALITY_KBPS

        val isLowQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in 1..LOW_QUALITY_MAX_KBPS
    }

    private val LOSSLESS_CODEC_SUFFIXES = listOf("flac", "alac", "raw")

    fun isLosslessMime(mimeType: String?): Boolean =
        mimeType != null && (LOSSLESS_CODEC_SUFFIXES.any { mimeType.endsWith(it) } || mimeType.startsWith("audio/dsd"))

    fun megahertzLabel(hz: Int): String? =
        if (hz < 1_000_000) null
        else java.math.BigDecimal(hz).movePointLeft(6).stripTrailingZeros().toPlainString() + " MHz"

    fun isDolbyAtmosMime(mimeType: String?): Boolean =
        mimeType != null && (mimeType.endsWith("eac3-joc") || mimeType.endsWith("eac3"))

    fun isRawPcm(mimeType: String?): Boolean =
        mimeType != null && (mimeType == "audio/raw" || mimeType.endsWith("wav", ignoreCase = true) || mimeType == "audio/x-wav")

    const val LOW_QUALITY_MAX_KBPS = 80

    const val HI_QUALITY_KBPS = 256

    fun codecLabel(mimeType: String?): String? {
        if (mimeType == null) return null
        return when {
            mimeType.endsWith("flac", ignoreCase = true) -> "FLAC"
            mimeType.endsWith("opus", ignoreCase = true) -> "Opus"
            mimeType.endsWith("mp4a-latm", ignoreCase = true) || mimeType.endsWith("aac", ignoreCase = true) -> "AAC"
            mimeType.endsWith("mpeg", ignoreCase = true) || mimeType.endsWith("mp3", ignoreCase = true) -> "MP3"
            mimeType.endsWith("alac", ignoreCase = true) -> "ALAC"
            mimeType.endsWith("raw", ignoreCase = true) -> "PCM"
            mimeType.startsWith("audio/dsd", ignoreCase = true) -> mimeType.substringAfter('/').uppercase()
            mimeType.endsWith("vorbis", ignoreCase = true) -> "Vorbis"
            mimeType.endsWith("eac3-joc", ignoreCase = true) -> "E-AC-3 JOC"
            mimeType.endsWith("eac3", ignoreCase = true) -> "E-AC-3"
            mimeType.contains("/") -> mimeType.substringAfterLast("/").uppercase()
            else -> mimeType.uppercase()
        }
    }

    private val generation = AtomicLong(1L)

    val currentGeneration: Long get() = generation.get()

    @Volatile
    private var activeMediaId: String? = null

    val current = MutableStateFlow<Snapshot?>(null)

    fun publish(snapshot: Snapshot, expectedGeneration: Long): Boolean {
        if (expectedGeneration != currentGeneration) return false
        val active = activeMediaId
        if (active != null && snapshot.mediaId != null && snapshot.mediaId != active) return false
        current.value = snapshot
        return true
    }

    val racingLossless = MutableStateFlow<Set<String>>(emptySet())

    fun onLosslessRaceStart(videoId: String) {
        racingLossless.update { it + videoId }
    }

    fun onLosslessRaceEnd(videoId: String) {
        racingLossless.update { it - videoId }
    }

    private val picked = ConcurrentHashMap<String, Int>()

    private val declared = ConcurrentHashMap<String, StreamFormat>()

    private val sources = ConcurrentHashMap<String, String>()

    fun onStreamPicked(videoId: String, kbps: Int, source: String? = null) {
        if (kbps > 0) {
            if (picked.size >= MAX_REMEMBERED) picked.clear()
            picked[videoId] = kbps
        }
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[videoId] = source
        }
    }

    fun onSourceStream(trackId: String?, format: StreamFormat, source: String? = null) {
        if (trackId.isNullOrBlank()) return
        if (declared.size >= MAX_REMEMBERED) declared.clear()
        declared[trackId] = format
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[trackId] = source
        }
    }

    fun recordSource(mediaId: String?, source: String?) {
        if (mediaId.isNullOrBlank() || source.isNullOrBlank()) return
        if (sources.size >= MAX_REMEMBERED) sources.clear()
        sources[mediaId] = source
    }

    fun exactSourceFor(mediaId: String?): String? = mediaId?.let { sources[it] }

    fun sourceFor(mediaId: String?): String? {
        val key = mediaId ?: return null
        return sources[key]
            ?: SourceTrackKeys.parse(key)?.second?.let { sources[it] }
    }

    fun pickedBitrateKbps(videoId: String?): Int? = videoId?.let { picked[it] }

    fun sourceBitrate(mediaId: String?, measuredKbps: () -> Int?): Pair<Int, TelemetryProvenance>? {
        val declared = declaredFormat(mediaId)
        declared?.takeUnless { it.kbpsInferred }?.kbps
            ?.let { return it to TelemetryProvenance.AUTHORITATIVE }
        if (declared?.isLossless == true) return null
        if (declared != null) {
            measuredKbps()?.let { return it to TelemetryProvenance.MEASURED }
            declared.kbps?.let { return it to TelemetryProvenance.DERIVED }
        }
        pickedBitrateKbps(mediaId)?.let { return it to TelemetryProvenance.AUTHORITATIVE }
        return measuredKbps()?.let { it to TelemetryProvenance.MEASURED }
    }

    fun clearDeclared(trackId: String?) {
        if (trackId != null) {
            declared.remove(trackId)
            sources.remove(trackId)
        }
    }

    fun declaredFormat(mediaId: String?): StreamFormat? {
        val key = mediaId ?: return null
        return declared[key]
            ?: SourceTrackKeys.parse(key)?.second?.let { declared[it] }
    }

    fun onTrackTransition(mediaId: String? = null) {
        generation.incrementAndGet()
        activeMediaId = mediaId
        current.value = null
    }

    fun forgetLastSession() {
        generation.incrementAndGet()
        activeMediaId = null
        current.value = null
        racingLossless.value = emptySet()
        picked.clear()
        declared.clear()
        sources.clear()
    }

    private const val MAX_REMEMBERED = 64
}
