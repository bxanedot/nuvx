package com.bxane.nuvx.data.sources.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull











@Serializable
data class AddonManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",

    val resources: List<String> = emptyList(),
    val settings: List<AddonSetting> = emptyList(),
    val search: AddonSearchEndpoint? = null,
    val stream: AddonStreamEndpoint? = null,
    val links: List<AddonLink> = emptyList(),





    val allowDownloads: JsonElement? = null,





    val checkValidLossless: JsonElement? = null,
) {

    val displayName: String get() = name.ifBlank { id.ifBlank { "Addon" } }

    val downloadsAllowed: Boolean get() = allowDownloads.asFlag() ?: true

    val requiresLosslessOutput: Boolean get() = checkValidLossless.asFlag() ?: false










    val isPlayable: Boolean get() =
        (search != null || resources.any { it.equals("search", ignoreCase = true) }) &&
            (stream != null || resources.any { it.equals("stream", ignoreCase = true) })
}

@Serializable
data class AddonSearchEndpoint(
    val path: String = "/search",
    val method: String = "GET",
)

@Serializable
data class AddonStreamEndpoint(
    val path: String = "/stream",
    val method: String = "GET",
)

@Serializable
data class AddonLink(
    val label: String = "",
    val url: String = "",
)

@Serializable
data class AddonSetting(
    val key: String = "",
    val type: String = "string",
    val label: String = "",
    val description: String = "",
    val default: String? = null,
    val options: List<AddonOption> = emptyList(),
) {
    val defaultValue: String? get() = default
}

@Serializable
data class AddonOption(
    val value: String = "",
    val label: String = "",
) {
    val stringValue: String get() = value.ifBlank { label }
}

@Serializable
data class AddonSearchResponse(
    val tracks: List<AddonTrack> = emptyList(),
    @SerialName("next") val next: String? = null,
)

@Serializable
data class AddonTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int? = null,
    val duration: Double? = null,
    val artworkUrl: String? = null,
    val artworkURL: String? = null,
    val albumArtworkURL: String? = null,
    val codec: String? = null,
    val format: String? = null,
    val audioQuality: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val explicit: Boolean = false,
    val lossless: Boolean = false,
    val year: Int? = null,
    val isrc: String? = null,
) {
    val effectiveDurationSec: Int? get() = durationSec ?: duration?.positiveInt()
    val effectiveArtworkUrl: String? get() = artworkUrl?.ifBlank { null }
        ?: artworkURL?.ifBlank { null } ?: albumArtworkURL?.ifBlank { null }
    val effectiveCodec: String? get() = codec?.ifBlank { null } ?: format?.ifBlank { null }








    val claimsLossless: Boolean
        get() = effectiveCodec?.lowercase() in LOSSLESS_CODECS ||
            (effectiveCodec == null && (lossless || audioQuality?.uppercase() in LOSSLESS_QUALITIES))

    private companion object {
        val LOSSLESS_QUALITIES = setOf("LOSSLESS", "HI_RES_LOSSLESS")
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
    }
}

@Serializable
data class AddonStream(
    val url: String = "",
    val codec: String? = null,
    val format: String? = null,
    val bitrateKbps: Int? = null,
    val bitrate: Double? = null,
    val sampleRateHz: Int? = null,
    val sampleRate: Double? = null,
    val bitDepth: Int? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),

    val encrypted: JsonElement? = null,







    val belowRequest: Boolean = false,
    val durationSec: Int? = null,

    val expiresAt: String? = null,
) {
    val effectiveCodec: String? get() = codec?.ifBlank { null } ?: format?.ifBlank { null }


    val effectiveSampleRateHz: Int? get() = sampleRateHz ?: sampleRate?.let {
        (if (it < 1000) it * 1000 else it).positiveInt()
    }
    val effectiveBitrateKbps: Int? get() = bitrateKbps ?: bitrate?.let {
        (if (it >= 10_000) it / 1000 else it).positiveInt()
    }

    val isEncrypted: Boolean
        get() {
            val value = encrypted ?: return false
            if (value is JsonNull) return false
            val primitive = value as? JsonPrimitive ?: return true
            primitive.booleanOrNull?.let { return it }
            return primitive.content.isNotBlank() &&
                !primitive.content.equals("false", ignoreCase = true) &&
                !primitive.content.equals("none", ignoreCase = true)
        }









    val isDolbyAtmos: Boolean
        get() = effectiveCodec?.lowercase() in setOf("eac3-joc", "ec3-joc", "dolby-atmos")
}

private fun Double.positiveInt(): Int? =
    takeIf { it.isFinite() && it > 0 && it <= Int.MAX_VALUE }?.toInt()?.takeIf { it > 0 }


internal fun JsonElement?.asFlag(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    primitive.intOrNull?.let { return it != 0 }
    return when (primitive.content.trim().lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }
}
