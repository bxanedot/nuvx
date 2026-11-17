package com.licht.nuvx.playback

import android.media.MediaDataSource
import com.licht.nuvx.data.Http
import com.licht.nuvx.data.TrackLog
import com.licht.nuvx.data.innertube.InnerTubeXResolver
import com.licht.nuvx.playback.smart.AudioDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream

internal object MusicVideoAlignmentAudio {

    private const val TAG = "MusicVideoAlignment"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_KBPS = 128
    private const val ANALYSIS_SECONDS = 90.0

    suspend fun envelope(videoId: String): FloatArray? = withContext(Dispatchers.IO) {
        val extracted = try {
            InnerTubeXResolver.extract(videoId, MAX_KBPS, requireM4a = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "video alignment: couldn't resolve the audio: ${e.message}", about = videoId)
            return@withContext null
        } ?: return@withContext null
        val bytes = download(extracted.url, extracted.headers, videoId) ?: return@withContext null
        val decoded = AudioDecoder.decodeRegion(ByteArraySource(bytes), 0.0, ANALYSIS_SECONDS) ?: return@withContext null
        musicVideoEnergyEnvelope(decoded.first.samples, 1, decoded.first.sampleRate.toInt())
    }

    private fun download(url: String, headers: Map<String, String>, videoId: String): ByteArray? {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return try {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TrackLog.w(TAG, "video alignment: audio fetch returned HTTP ${response.code}", about = videoId)
                    return null
                }
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val declared = body.contentLength().coerceIn(1024L, 4L * 1024 * 1024).toInt()
                val out = ByteArrayOutputStream(declared)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) return null
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "video alignment: audio fetch failed: ${e.message}", about = videoId)
            null
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }
}
