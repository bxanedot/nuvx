package com.metrolist.innertubex

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable


public suspend fun HttpResponse.bodyAsTextLimited(maxBytes: Int): String = bodyAsTextLimited(maxBytes, stopWhen = null)






internal suspend fun HttpResponse.bodyAsTextLimited(
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)?,
): String {
    require(maxBytes > 0) { "Maximum response size must be positive" }
    val channel = bodyAsChannel()
    val declaredLength = contentLength()
    if (stopWhen == null && declaredLength != null && declaredLength > maxBytes) {
        channel.cancel(null)
        throw ResponseTooLargeException(maxBytes)
    }

    val readBuffer = ByteArray(minOf(DEFAULT_READ_BUFFER_SIZE, maxBytes))

    val initialCapacity =
        (declaredLength ?: DEFAULT_READ_BUFFER_SIZE.toLong())
            .coerceIn(0L, minOf(maxBytes.toLong(), if (stopWhen != null) FIRST_STOP_CHECK_BYTES * 2L else maxBytes.toLong()))
            .toInt()
    var bytes = ByteArray(initialCapacity)
    var size = 0
    var nextCheck = FIRST_STOP_CHECK_BYTES.toLong()
    try {
        while (true) {
            val count = channel.readAvailable(readBuffer, 0, readBuffer.size)
            if (count == -1) break
            if (count == 0) continue
            if (size > maxBytes - count) {
                throw ResponseTooLargeException(maxBytes)
            }
            if (size + count > bytes.size) {
                bytes = bytes.copyOf(minOf(maxBytes.toLong(), maxOf(size.toLong() + count, bytes.size * 2L)).toInt())
            }
            readBuffer.copyInto(bytes, destinationOffset = size, endIndex = count)
            size += count
            if (stopWhen != null && size >= nextCheck) {
                nextCheck = size * 2L
                val prefix = bytes.decodeToString(endIndex = size)
                if (stopWhen(prefix)) {
                    channel.cancel(null)
                    return prefix
                }
            }
        }
    } catch (error: Throwable) {
        channel.cancel(error)
        throw error
    }
    return bytes.decodeToString(endIndex = size)
}


internal class ResponseTooLargeException(
    maxBytes: Int,
) : IllegalStateException("Response exceeded the $maxBytes byte limit")

private const val DEFAULT_READ_BUFFER_SIZE = 8 * 1024
private const val FIRST_STOP_CHECK_BYTES = 32 * 1024
