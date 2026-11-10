package com.bxane.nuvx.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

object MediaTagger {

    private const val TAG = "nuvx"

    private const val COVER_MAX_SIDE = 1000

    private const val FLAC_HEAD_BYTES = 16 * 1024 * 1024

    private const val COPY_BUFFER = 64 * 1024

    private val TAGGABLE = setOf("m4a", "webm", "flac")

    fun carriesTags(extension: String): Boolean = extension in TAGGABLE

    internal class Artwork(val bytes: ByteArray, val mime: String)

    internal fun artworkFor(context: Context, track: Song): Artwork? {
        val thumbnailUrl = track.thumbnailUrl
        if (thumbnailUrl.isNullOrBlank()) {
            Log.d(TAG, "no artwork url for ${track.videoId}; saving it without a cover")
            return null
        }
        cachedArtwork(context, thumbnailUrl)?.let { return it }
        return fetchCover(track)
    }

    private val taggingLock = Mutex()

    private val artworkFileLock = Any()

    internal fun persistArtwork(context: Context, sourceUrl: String?, artwork: Artwork?): String? {
        if (artwork == null || artwork.bytes.isEmpty()) return null
        return runCatching {
            val folder = File(context.filesDir, "download-artwork")
            val target = artworkFile(context, sourceUrl, artwork)
            val name = target.name
            synchronized(artworkFileLock) {
                if (!target.exists()) {
                    if (!folder.exists() && !folder.mkdirs()) error("Could not create artwork folder")
                    val temp = File(folder, ".$name-${System.nanoTime()}.part")
                    try {
                        temp.writeBytes(artwork.bytes)
                        if (!temp.renameTo(target) && !target.exists()) {
                            temp.copyTo(target, overwrite = false)
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
            Uri.fromFile(target).toString()
        }.onFailure {
            Log.w(TAG, "could not save artwork sidecar: ${it.message}")
        }.getOrNull()
    }

    internal fun cacheArtwork(context: Context, sourceUrl: String?): String? {
        if (sourceUrl.isNullOrBlank()) return null
        artworkFile(context, sourceUrl).takeIf { it.isFile }?.let { return Uri.fromFile(it).toString() }
        val artwork = fetchCover(sourceUrl.artworkAt(1200) ?: sourceUrl, "collection") ?: return null
        return persistArtwork(context, sourceUrl, artwork)
    }

    private fun cachedArtwork(context: Context, sourceUrl: String): Artwork? = runCatching {
        artworkFile(context, sourceUrl).takeIf { it.isFile }
            ?.readBytes()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Artwork(it, "image/jpeg") }
    }.getOrNull()

    private fun artworkFile(context: Context, sourceUrl: String?, artwork: Artwork? = null): File {
        val name: String
        if (sourceUrl.isNullOrBlank()) {
            name = MessageDigest.getInstance("SHA-256")
                .digest(artwork?.bytes ?: byteArrayOf())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".jpg"
        } else {
            name = MessageDigest.getInstance("SHA-256")
                .digest(sourceUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".jpg"
        }
        return File(File(context.filesDir, "download-artwork"), name)
    }

    internal suspend fun embed(
        context: Context,
        uri: Uri,
        track: Song,
        extension: String,
        lyrics: LyricsTag.Embeddable? = null,
        artwork: Artwork? = null,
    ) {
        if (!carriesTags(extension)) return
        taggingLock.withLock { embedNow(context, uri, track, extension, lyrics, artwork) }
    }

    private fun embedNow(
        context: Context,
        uri: Uri,
        track: Song,
        extension: String,
        lyrics: LyricsTag.Embeddable?,
        artwork: Artwork?,
    ) {
        val plain = lyrics?.plain
        val words = lyrics?.enhanced

        if (extension == "flac" &&
            tagFlacStreaming(context, uri, track, plain, words, artwork)
        ) {
            return
        }

        val original = readAll(context, uri) ?: return
        val tagged = runCatching {
            when (extension) {
                "m4a" -> Mp4Tagger.tag(
                    Mp4Sidx.ensure(original),
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    coverIsPng = false,
                    wordLyrics = words,
                )
                "flac" -> FlacTagger.tag(
                    original,
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    artwork?.mime ?: "image/jpeg",
                    wordLyrics = words,
                )
                else -> WebmTagger.tag(
                    original,
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    artwork?.mime ?: "image/jpeg",
                    wordLyrics = words,
                )
            }
        }.onFailure {
            Log.w(TAG, "could not build tags for ${track.videoId} (${original.size}B $extension): $it")
        }.getOrNull() ?: return

        if (tagged === original) return
        writeAll(context, uri, tagged)
    }

    private fun tagFlacStreaming(
        context: Context,
        uri: Uri,
        track: Song,
        lyrics: String?,
        wordLyrics: String?,
        artwork: Artwork?,
    ): Boolean {
        val head = readPrefix(context, uri, FLAC_HEAD_BYTES) ?: return false
        val wholeFile = head.size < FLAC_HEAD_BYTES
        val rewrite = FlacTagger.header(
            head,
            track.title,
            track.artist,
            track.albumName,
            lyrics,
            artwork?.bytes,
            artwork?.mime ?: "image/jpeg",
            wordLyrics = wordLyrics,
        ) ?: return wholeFile

        val temp = File(context.cacheDir, "tagging-${track.videoId.hashCode()}-${System.nanoTime()}.flac")
        return runCatching {
            temp.outputStream().buffered().use { sink ->
                sink.write(rewrite.metadata)
                openInput(context, uri)?.use { source ->
                    source.skipFully(rewrite.audioStart.toLong())
                    source.copyTo(sink, COPY_BUFFER)
                } ?: error("could not reopen $uri")
            }
            replaceWith(context, uri, temp)
            true
        }.onFailure {
            Log.w(TAG, "streaming flac tag failed for ${track.videoId}: ${it.message}")
            temp.delete()
        }.getOrDefault(false)
    }

    private fun replaceWith(context: Context, uri: Uri, temp: File) {
        if (uri.scheme == "file") {
            val target = File(requireNotNull(uri.path))
            if (temp.renameTo(target)) return
        }
        temp.inputStream().use { source ->
            val sink = if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).outputStream()
            } else {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("could not open $uri for writing")
            }
            sink.use { source.copyTo(it, COPY_BUFFER) }
        }
        temp.delete()
    }

    private fun readPrefix(context: Context, uri: Uri, max: Int): ByteArray? = runCatching {
        openInput(context, uri)?.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER)
            while (out.size() < max) {
                val read = source.read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.onFailure { Log.w(TAG, "could not read the start of $uri: ${it.message}") }.getOrNull()

    private fun openInput(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (read() == -1) error("file ended ${left}B before its audio frames")
            left--
        }
    }

    private fun fetchCover(track: Song): Artwork? {
        val url = track.artworkAt(1200) ?: return null
        return fetchCover(url, track.videoId)
    }

    private fun fetchCover(url: String, logId: String): Artwork? {
        return runCatching {
            val request = okhttp3.Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val raw = response.body?.bytes() ?: return@runCatching null
                val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@runCatching null
                val scaled = downscale(bitmap, COVER_MAX_SIDE)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
                Artwork(out.toByteArray(), "image/jpeg")
            }
        }.onFailure { Log.d(TAG, "no cover saved for $logId: ${it.message}") }.getOrNull()
    }

    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? = runCatching {
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }.onFailure { Log.w(TAG, "could not read $uri for tagging: ${it.message}") }.getOrNull()

    private fun writeAll(context: Context, uri: Uri, bytes: ByteArray) {
        runCatching {
            if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).writeBytes(bytes)
            } else {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            }
        }.onFailure { Log.w(TAG, "could not write tags to $uri: ${it.message}") }
    }
}
