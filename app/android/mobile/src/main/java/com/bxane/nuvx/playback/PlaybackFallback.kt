package com.bxane.nuvx.playback

import android.os.Bundle
import androidx.media3.common.MediaItem

internal object PlaybackFallback {

    fun isAlternative(uri: String, substitutedYouTube: Boolean): Boolean {
        if (parameter(uri, DIRECT_YOUTUBE_PARAMETER) == "1") return false
        if (uri.substringBefore('?').substringBefore('#') == "nuvx://source") return true
        val rendition = parameter(uri, QualityUpgrade.MARKER)
        return substitutedYouTube || rendition == "hifi" || rendition?.startsWith("hifi-") == true
    }

    fun directYouTubeSourceUri(uri: String): String {
        val fragmentAt = uri.indexOf('#')
        val withoutFragment = if (fragmentAt >= 0) uri.substring(0, fragmentAt) else uri
        val fragment = if (fragmentAt >= 0) uri.substring(fragmentAt) else ""
        val queryAt = withoutFragment.indexOf('?')
        val base = if (queryAt >= 0) withoutFragment.substring(0, queryAt) else withoutFragment
        val kept = if (queryAt >= 0) {
            withoutFragment.substring(queryAt + 1)
                .split('&')
                .filter { part ->
                    val key = part.substringBefore('=')
                    key != DIRECT_YOUTUBE_PARAMETER &&
                        key != QualityUpgrade.MARKER &&
                        key != MANIFEST_REOPEN_PARAMETER
                }
                .filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val params = kept + "$DIRECT_YOUTUBE_PARAMETER=1" + "${QualityUpgrade.MARKER}=original"
        return "$base?${params.joinToString("&")}$fragment"
    }

    private fun parameter(uri: String, wanted: String): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == wanted }
            ?.substringAfter('=', missingDelimiterValue = "")
    }

    private const val MANIFEST_REOPEN_PARAMETER = "manifest_reopen"
}

internal fun MediaItem.toYouTubeFallbackMediaItem(): MediaItem? {
    val song = toSong()
    if (song.hasYouTubeOriginal()) return song.toDirectYouTubeMediaItem()

    val currentUri = localConfiguration?.uri ?: return null
    if (currentUri.authority != "source") return null
    val cleanMetadata = mediaMetadata.buildUpon()
        .setExtras(Bundle(mediaMetadata.extras ?: Bundle()).apply {
            remove(EXTRA_QUALITY_UPGRADED)
        })
        .build()
    return buildUpon()
        .setUri(PlaybackFallback.directYouTubeSourceUri(currentUri.toString()))
        .setMimeType(null)
        .setMediaMetadata(cleanMetadata)
        .build()
}
