package com.bxane.nuvx.playback

import android.net.Uri
import android.util.Log
import com.bxane.nuvx.data.TrackLog
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.bxane.nuvx.data.innertube.PlayerClient
import com.bxane.nuvx.data.innertube.StreamResolver
import java.io.InterruptedIOException

@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var rangeBytes = chunkBytes

    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position

        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            passthrough = true
            chunkOpen = true
            try {
                return upstream.open(dataSpec)
            } catch (e: Exception) {
                if (e !is InterruptedIOException) {
                    val who = dataSpec.uri.host ?: dataSpec.uri.toString().take(120)
                    TrackLog.w(TAG, "$who refused the stream: ${e.message}")
                    report(dataSpec, e)
                }
                throw e
            }
        }

        passthrough = false
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            total
        } else {
            minOf(total, position + dataSpec.length)
        }
        bytesRemaining = (end - position).coerceAtLeast(0L)
        rangeBytes = minOf(chunkBytes, PlayerClient.rangeBytesFor(dataSpec.uri.toString()))
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            upstream.open(spec)
        } catch (e: Exception) {
            if (e !is InterruptedIOException) {
                TrackLog.w(
                    TAG,
                    "range $position-${position + length - 1} refused for " +
                        "${spec.uri.getQueryParameter("c")}: ${e.message}",
                )
                report(spec, e)
            }
            throw e
        }
        chunkRemaining = length
        chunkOpen = true
    }

    private fun report(spec: DataSpec, e: Exception) {
        if (e is HttpDataSource.InvalidResponseCodeException) {
            StreamResolver.onPlaybackRefused(spec.uri.toString(), e.responseCode)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    private companion object {
        const val TAG = "nuvx"

        const val MAX_EMPTY_RANGES = 3
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }
}
