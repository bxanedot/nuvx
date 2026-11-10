package com.bxane.nuvx.playback

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.smb.SmbClient
import com.bxane.nuvx.data.smb.SmbConfig
import java.io.IOException

class SmbDataSource : BaseDataSource(true) {

    private var file: com.hierynomus.smbj.share.File? = null
    private var position = 0L
    private var endPosition = 0L
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        require(uri.scheme == SmbConfig.SCHEME) { "SmbDataSource cannot open ${uri.scheme}" }
        val path = displayPath(uri.toString()) ?: throw IOException("Not an SMB track: $uri")
        try {
            val handle = SmbClient.openFile(path)
            val length = handle.length
            position = dataSpec.position.coerceAtLeast(0)
            endPosition = if (dataSpec.length != -1L) {
                (position + dataSpec.length).coerceAtMost(length)
            } else {
                length
            }.coerceAtLeast(position)
            file = handle
            openedUri = uri
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not open SMB file: ${e.message}", e)
        }
        transferStarted(dataSpec)
        NerdStats.recordSource(dataSpec.key, "SMB")
        return endPosition - position
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        val handle = file ?: return -1
        if (position >= endPosition) return -1
        val capped = minOf(readLength.toLong(), endPosition - position).toInt()
        val read = try {
            handle.read(buffer, position, offset, capped)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("SMB read failed: ${e.message}", e)
        }
        if (read <= 0) return -1
        position += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        runCatching { file?.close() }
        file = null
        transferEnded()
    }

    internal fun displayPath(url: String): String? =
        SmbConfig.relativePath(url, com.bxane.nuvx.data.smb.SmbAuth.share)

    class RoutingFactory(private val downstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(downstream.createDataSource())

        private class RoutingDataSource(private val inner: DataSource) : DataSource {
            private val smb = SmbDataSource()
            private var active: DataSource = inner

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                inner.addTransferListener(transferListener)
                smb.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                active = if (dataSpec.uri.scheme == SmbConfig.SCHEME) smb else inner
                return active.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                active.read(buffer, offset, length)

            override fun getUri(): Uri? = active.uri

            override fun close() = active.close()
        }
    }
}
