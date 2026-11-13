package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song





























data class StreamFormat(

    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val verified: Boolean = false,
) {

    private val normalizedCodec: String?
        get() {
            val fields = codec?.lowercase()?.split(';') ?: return null
            val raw = fields.drop(1)
                .firstOrNull { it.trim().startsWith("codecs=") }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"', '\'')
                ?.substringBefore(',')
                ?: fields.first().trim()
            if (raw.isEmpty()) return null
            val name = raw.substringAfterLast('/').removePrefix("x-")
            return when (name) {
                "wavpack" -> "wv"
                "dsd", "dsf", "dff" -> "dsd"
                "e-ac-3", "ec-3" -> "eac3"
                "ec3-joc" -> "eac3-joc"
                "ac-3" -> "ac3"
                "dts-hd", "vnd.dts", "vnd.dts.hd" -> "dts"
                "vnd.dolby.dd-ec3" -> "eac3"
                "true-hd", "mlp" -> "truehd"
                "x-ms-wma", "ms-wma" -> "wma"
                "wave", "x-wav" -> "wav"
                "x-aiff" -> "aiff"
                else -> name
            }
        }

    internal val codecFamily: String?
        get() = when (val name = normalizedCodec) {
            "mpeg", "mpga", "mp3" -> "mp3"
            "aac", "aac-lc", "heaac" -> "aac"
            else -> if (name?.startsWith("mp4a.") == true) "aac" else name
        }









    val isLossless: Boolean?
        get() = normalizedCodec?.let { it in LOSSLESS_CODECS }


    val isDolbyAtmos: Boolean
        get() = normalizedCodec in DOLBY_ATMOS_CODECS






    val isKnownUnsupportedByNativeDecoder: Boolean
        get() = normalizedCodec?.let { it in UNSUPPORTED_NATIVE_CODECS } == true


    val summary: String
        get() = if (isDolbyAtmos) {
            "Dolby Atmos"
        } else {
            listOfNotNull(
                codec?.uppercase(),
                bitDepth?.let { "$it-bit" },
                sampleRateHz?.let { "${khz(it)} kHz" },
                kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
            ).joinToString(" · ").ifEmpty { "Unknown format" }
        }










    private fun khz(hz: Int): String {
        val tenths = hz / 100
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0) "$whole" else "$whole.$fraction"
    }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsd")
        val DOLBY_ATMOS_CODECS = setOf("eac3-joc", "dolby-atmos")
        val UNSUPPORTED_NATIVE_CODECS = setOf(
            "ape", "wv", "dsd", "eac3", "eac3-joc", "dolby-atmos", "ac3",
            "dts", "mpc", "wma", "truehd",
        )
    }
}







data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    val headers: Map<String, String> = emptyMap(),










    val belowRequest: Boolean = false,











    val durationSec: Int? = null,









    val sourceConfigId: String? = null,
)









sealed interface StreamRequest {

    data object Lossless : StreamRequest


    data class Capped(val capKbps: Int) : StreamRequest


    data object Best : StreamRequest


    val kbpsCeiling: Int? get() = (this as? Capped)?.capKbps


    val tier: String
        get() = when (this) {
            is Lossless -> "LOSSLESS"
            is Best -> "BEST"
            is Capped -> if (capKbps >= 256) "HIGH" else "MEDIUM"
        }
}











sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth
    data class Unreachable(val reason: String) : SourceHealth
    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}












interface MusicSource {


    val configId: String

    val kind: SourceKind


    val displayName: String


    suspend fun health(): SourceHealth



















    suspend fun search(
        query: String,
        limit: Int = 25,
        waitForAll: Boolean = false,
        request: StreamRequest? = null,
    ): List<Song>







    suspend fun stream(trackId: String, request: StreamRequest): SourceStream?
}


interface ConfigBacked {
    val config: SourceConfig
}
