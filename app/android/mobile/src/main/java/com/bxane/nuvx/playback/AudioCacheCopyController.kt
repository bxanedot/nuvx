package com.bxane.nuvx.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

@UnstableApi
internal class AudioCacheCopyController(
    private val cacheProvider: () -> SimpleCache,
    private val cacheInitialized: () -> Boolean,
    private val keysOf: (String) -> List<String>,
    private val isComplete: (String) -> Boolean,
) {
    private val cache: SimpleCache get() = cacheProvider()

    fun completeCopyOf(mediaId: String): AudioCache.CompleteCopy? {
        if (!cacheInitialized() || mediaId.isBlank()) return null
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .filterNot { key ->
                runCatching { cache.getContentMetadata(key).get(AudioCacheMetadataKeys.META_ORIGIN, null as String?) }
                    .getOrNull() == AudioCache.Origin.OTHER.name
            }
            .mapNotNull { key ->
                val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
                    .getOrDefault(C.LENGTH_UNSET.toLong())
                if (length <= 0 || !cache.isCached(key, 0, length)) return@mapNotNull null
                val (extension, mime) = containerOf(key) ?: return@mapNotNull null
                AudioCache.CompleteCopy(key, length, extension, mime)
            }
            .maxByOrNull { it.length }
    }

    private fun containerOf(key: String): Pair<String, String>? {
        val head = cache.getCachedSpans(key)
            .firstOrNull { it.position == 0L && it.isCached }
            ?.file
            ?.takeIf { it.exists() }
            ?: return null
        val magic = ByteArray(CONTAINER_MAGIC_BYTES)
        val read = runCatching { head.inputStream().use { it.read(magic) } }.getOrDefault(-1)
        if (read < magic.size) return null
        return cachedContainerOf(magic)
    }

    suspend fun copyOut(
        copy: AudioCache.CompleteCopy,
        sink: java.io.OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("nuvx://cache"))
            .setKey(copy.key)
            .setPosition(0)
            .setLength(copy.length)
            .build()
        try {
            source.open(spec)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (written < copy.length) {
                ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, copy.length)
            }
            if (written < copy.length) {
                throw IOException("cached copy ended at $written of ${copy.length} bytes")
            }
        } finally {
            source.close()
        }
    }

    fun keepsAddonCopy(key: String): Boolean =
        !key.contains("#hifi") && isComplete(key) && containerOf(key) != null

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
