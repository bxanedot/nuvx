package com.bxane.nuvx.download

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.innertube.PlayerClient
import com.bxane.nuvx.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Downloader {

    private const val TAG = "nuvx"

    private const val CHUNK_BYTES = 2L * 1024 * 1024

    private const val BUFFER_BYTES = 64 * 1024

    suspend fun fetch(
        videoId: String,
        stream: StreamResolver.Stream,
        maxKbps: Int,
        requireM4a: Boolean,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var url = stream.url
        val total = contentLength(url) ?: error("Track unavailable: no length to fetch")

        var position = 0L
        var reresolved = false
        val buffer = ByteArray(BUFFER_BYTES)

        while (position < total) {
            coroutineContext.ensureActive()
            val length = minOf(CHUNK_BYTES, PlayerClient.rangeBytesFor(url), total - position)

            val response = try {
                open(url, position, length)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "range at $position failed for $videoId: ${e.message}")
                throw e
            }

            if (response.code in REFUSAL_CODES) {
                response.close()
                StreamResolver.onPlaybackRefused(url, response.code)
                if (reresolved) error("Download refused after ${position}B (HTTP ${response.code})")
                reresolved = true
                Log.w(TAG, "re-resolving $videoId after HTTP ${response.code} at $position")
                url = StreamResolver.resolveForDownload(videoId, maxKbps, requireM4a).url
                if (contentLength(url) != total) error("The stream changed mid-download — try again")
                continue
            }

            response.use {
                val requestedEnd = position + length - 1
                val chunkBytes = when (it.code) {
                    206 -> {
                        val range = CONTENT_RANGE.matchEntire(it.header("Content-Range").orEmpty())
                            ?: error("Download returned an invalid byte range")
                        val start = range.groupValues[1].toLong()
                        val end = range.groupValues[2].toLong()
                        val receivedTotal = range.groupValues[3].toLongOrNull()
                            ?: error("Download returned an unknown total size")
                        if (start != position || end != requestedEnd || receivedTotal != total) {
                            error("Download returned an unexpected byte range")
                        }
                        length
                    }
                    200 -> {
                        if (position != 0L) error("The server ignored the download range at ${position}B")
                        if (it.header("Content-Length")?.toLongOrNull()?.let { size -> size != total } == true) {
                            error("Download returned an unexpected file size")
                        }
                        total
                    }
                    else -> error("Download failed (HTTP ${it.code})")
                }
                val body = it.body ?: error("Download failed: empty response")
                val source = body.byteStream()
                var readForChunk = 0L
                while (readForChunk < chunkBytes) {
                    coroutineContext.ensureActive()
                    val wanted = minOf(buffer.size.toLong(), chunkBytes - readForChunk).toInt()
                    val read = source.read(buffer, 0, wanted)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    readForChunk += read
                    position += read
                    onProgress(position, total)
                }
                if (readForChunk != chunkBytes) error("Download stopped at ${position}B of $total")
                if (source.read() != -1) error("Download returned more bytes than requested")
            }
        }

        sink.flush()
        position
    }

    suspend fun fetchDirect(
        url: String,
        headers: Map<String, String>,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) error("Download failed (HTTP ${response.code})")
            val body = response.body ?: error("Download failed: empty response")
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            val source = body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L

            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                written += read
                if (total != null) onProgress(written, total)
            }
            sink.flush()

            if (total != null && written < total) {
                error("Download stopped at ${written}B of $total — try again")
            }
            if (written == 0L) error("Download failed: nothing was sent")
            Log.d(TAG, "fetched ${written}B directly")
            written
        }
    }

    internal enum class Container(val extension: String, val mimeType: String, val codec: String?) {
        FLAC("flac", "audio/flac", "flac"),
        WAV("wav", "audio/x-wav", "wav"),
        MP4("m4a", "audio/mp4", null),
        MP3("mp3", "audio/mpeg", "mp3"),
        ADTS("aac", "audio/aac", "aac"),
        OGG("ogg", "audio/ogg", null),
        WEBM("webm", "audio/webm", null),
    }

    internal sealed interface Probe {
        data class File(val container: Container, val totalBytes: Long?) : Probe

        data class Manifest(val dash: Boolean) : Probe
    }

    internal suspend fun probe(
        url: String,
        headers: Map<String, String>,
        statedCodec: String? = null,
    ): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            Http.client.newCall(request).awaitResponse().use { response ->
                if (response.code !in 200..299) {
                    Log.w(TAG, "probe refused (HTTP ${response.code})")
                    return@use null
                }
                val total = if (response.code == 206) {
                    response.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                } else {
                    response.header("Content-Length")?.toLongOrNull()
                }?.takeIf { it > 0 }
                val source = response.body?.source() ?: return@use null
                source.request(PROBE_BYTES.toLong())
                val head = source.buffer.readByteArray(minOf(source.buffer.size, PROBE_BYTES.toLong()))
                sniff(head, response.header("Content-Type").orEmpty(), statedCodec, total)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "could not probe source file: ${it.message}")
        }.getOrNull()
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    internal fun sniff(head: ByteArray, contentType: String, statedCodec: String?, totalBytes: Long?): Probe? {
        val type = contentType.lowercase()
        val text = String(head, Charsets.ISO_8859_1).removePrefix("ï»¿").trimStart()
        if ("mpegurl" in type || text.startsWith("#EXTM3U")) return Probe.Manifest(dash = false)
        if ("dash+xml" in type || "<MPD" in text) return Probe.Manifest(dash = true)
        return containerOf(head, statedCodec)?.let { Probe.File(it, totalBytes) }
    }

    private fun containerOf(head: ByteArray, statedCodec: String?): Container? {
        fun at(offset: Int, ascii: String) = head.size >= offset + ascii.length &&
            ascii.indices.all { head[offset + it] == ascii[it].code.toByte() }
        return when {
            at(0, "fLaC") -> Container.FLAC
            at(0, "RIFF") && at(8, "WAVE") -> Container.WAV
            at(4, "ftyp") -> Container.MP4
            at(0, "OggS") -> Container.OGG
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> Container.WEBM
            at(0, "ID3") -> if (statedCodec.equals("aac", ignoreCase = true)) Container.ADTS else Container.MP3
            head.size >= 2 && head[0] == 0xFF.toByte() -> {
                val second = head[1].toInt() and 0xFF
                when {
                    second and 0xF6 == 0xF0 -> Container.ADTS
                    second and 0xE0 == 0xE0 && second and 0x06 != 0 -> Container.MP3
                    else -> null
                }
            }
            else -> null
        }
    }

    private const val PROBE_BYTES = 512

    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

    private fun open(url: String, position: Long, length: Long) = Http.client
        .newCall(
            Request.Builder()
                .url(url)
                .header("Range", "bytes=$position-${position + length - 1}")
                .apply {
                    StreamResolver.mediaHeadersFor(url)
                        .forEach { (name, value) -> header(name, value) }
                }
                .build(),
        )
        .execute()

    private fun contentLength(url: String): Long? {
        url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }

        return runCatching {
            open(url, 0, 1).use { response ->
                when (response.code) {
                    206 -> response.header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    200 -> response.header("Content-Length")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    else -> null
                }
            }
        }.onFailure { Log.w(TAG, "could not measure the track: ${it.message}") }.getOrNull()
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)
}
