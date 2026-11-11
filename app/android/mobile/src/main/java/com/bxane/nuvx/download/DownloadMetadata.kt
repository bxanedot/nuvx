package com.bxane.nuvx.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bxane.nuvx.data.model.Song

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val uri: String,
    val downloadFormat: String? = null,
    val dateAddedSeconds: Long? = null,
)

internal fun resolvedDownloadDates(
    persistedAddedSeconds: Long?,
    fileModifiedMillis: Long?,
): Pair<Long?, Long?> {
    val modifiedSeconds = fileModifiedMillis?.takeIf { it > 0 }?.div(1_000)
    return (persistedAddedSeconds ?: modifiedSeconds) to modifiedSeconds
}

internal fun youtubeDownloadBadge(extension: String, kbps: Int): String? {
    if (kbps <= 0) return null
    val codec = if (extension.equals("m4a", ignoreCase = true)) "AAC" else "OPUS"
    return "$codec · $kbps kbps"
}

internal fun legacyYoutubeDownloadBadge(context: Context, uri: Uri): String? {
    if (!uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".webm", ignoreCase = true)) {
        return null
    }
    val bitsPerSecond = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull() ?: return null
    val kbps = ((bitsPerSecond + 500L) / 1_000L).toInt()
    return youtubeDownloadBadge("webm", kbps)
}

data class DownloadTarget(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    val artist: Boolean = false,
)

@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    val artist: Boolean = false,
    val videoIds: List<String> = emptyList(),
)

data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
    val artist: Boolean = false,
)
