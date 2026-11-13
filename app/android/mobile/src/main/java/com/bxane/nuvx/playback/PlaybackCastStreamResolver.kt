package com.bxane.nuvx.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.playback.cast.CastPlayback
import com.bxane.nuvx.playback.cast.CastStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
internal class PlaybackCastStreamResolver(
    private val streamResolver: () -> ResolvingDataSource.Resolver?,
) {
    suspend fun resolve(item: MediaItem): CastStream? = withContext(Dispatchers.IO) {
        val uri = item.localConfiguration?.uri ?: return@withContext null
        val resolver = streamResolver() ?: return@withContext null
        val resolved = DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(uri)) }.uri
        if (resolved.scheme == "http" || resolved.scheme == "https") {
            CastStream(resolved.toString(), CastPlayback.mimeTypeOf(resolved))
        } else {
            val fallback = if (uri.scheme == "content" || uri.scheme == "file") {
                item.mediaId.takeIf { it.isNotBlank() }?.let { vid ->
                    if (Downloads.saved.value.containsKey(vid)) "nuvx://watch?v=$vid" else null
                }
            } else null
            if (fallback != null) {
                runCatching {
                    DirectSourceGate.ungated { resolver.resolveDataSpec(DataSpec(fallback.toUri())) }.uri
                        .takeIf { it.scheme == "http" || it.scheme == "https" }
                }.getOrNull()?.let { url ->
                    CastStream(url.toString(), CastPlayback.mimeTypeOf(url))
                }
            } else null
        }
    }
}
