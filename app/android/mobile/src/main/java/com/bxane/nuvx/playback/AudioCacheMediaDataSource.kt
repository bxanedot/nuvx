package com.bxane.nuvx.playback

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

internal object AudioCacheNoUpstream : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long =
            throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
        override fun getUri(): Uri? = null
        override fun close() {}
    }
}

internal class AudioCacheMediaDataSource(
    private val dataSource: DataSource,
    private val uri: Uri,
) : MediaDataSource() {
    private var isOpen = false
    private var openPosition = -1L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (!isOpen || position != openPosition) {
            closeUpstream()
            val available = try {
                dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            } catch (error: IOException) {
                return -1
            }
            isOpen = true
            openPosition = position
            if (available == 0L) return -1
        }
        val count = try {
            dataSource.read(buffer, offset, size)
        } catch (error: IOException) {
            closeUpstream()
            return -1
        }
        if (count == C.RESULT_END_OF_INPUT) {
            closeUpstream()
            return -1
        }
        openPosition += count
        return count
    }

    override fun getSize(): Long {
        closeUpstream()
        return try {
            dataSource.open(DataSpec(uri))
        } catch (error: IOException) {
            -1L
        } finally {
            closeUpstream()
        }
    }

    override fun close() {
        closeUpstream()
    }

    private fun closeUpstream() {
        if (isOpen) {
            runCatching { dataSource.close() }
            isOpen = false
        }
    }
}
