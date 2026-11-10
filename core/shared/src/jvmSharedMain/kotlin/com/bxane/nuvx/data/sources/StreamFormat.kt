package com.bxane.nuvx.data.sources

import java.util.Locale

data class StreamFormat(
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val kbpsInferred: Boolean = false,
) {
    val isLossless: Boolean?
        get() = codec?.let { it in LOSSLESS_CODECS }

    val isDolbyAtmos: Boolean
        get() = codec in DOLBY_ATMOS_CODECS

    val summary: String
        get() = if (isDolbyAtmos) "Dolby Atmos" else listOfNotNull(
            codec?.uppercase(Locale.ROOT),
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" },
            kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
        ).joinToString(" · ").ifEmpty { "Unknown format" }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
        val DOLBY_ATMOS_CODECS = setOf("eac3-joc", "ec3-joc", "dolby-atmos")
    }
}
