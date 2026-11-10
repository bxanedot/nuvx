package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object StreamLengthProbe {

    suspend fun bytesOf(stream: SourceStream): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(stream.url)
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull()
                    200 -> response.header("Content-Length")?.toLongOrNull()
                    else -> null
                }?.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun tooShort(bytes: Long, format: StreamFormat, expectedSec: Int, slackSec: Int): Boolean {
        if (bytes <= 0 || expectedSec <= 0) return false
        val floorKbps = if (format.isLossless == true) {
            LOSSLESS_FLOOR_KBPS
        } else {
            minOf(format.kbps?.takeIf { it > 0 } ?: LOSSY_FLOOR_KBPS, LOSSY_FLOOR_KBPS)
        }
        val mostSec = bytes * 8.0 / (floorKbps * 1000.0)
        return mostSec < expectedSec - slackSec
    }

    private const val LOSSLESS_FLOOR_KBPS = 200

    private const val LOSSY_FLOOR_KBPS = 64

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_TIMEOUT_SEC = 4L
}
