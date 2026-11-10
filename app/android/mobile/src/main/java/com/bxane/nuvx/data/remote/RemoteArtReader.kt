package com.bxane.nuvx.data.remote

import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.smb.SmbClient
import com.bxane.nuvx.data.smb.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object RemoteArtReader {

    fun http(url: String): EmbeddedArt.Reader = object : EmbeddedArt.Reader {
        override val size: Long = Long.MAX_VALUE

        override fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0 || offset < 0) return ByteArray(0)
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .get()
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    throw java.io.IOException("Range read failed with ${response.code}")
                }
                if (response.code == 200 && offset > 0) {
                    return ByteArray(0)
                }
                val body = response.body ?: return ByteArray(0)
                val buffer = okio.Buffer()
                var remaining = length.toLong()
                body.source().use { source ->
                    while (remaining > 0) {
                        val read = source.read(buffer, remaining)
                        if (read == -1L) break
                        remaining -= read
                    }
                }
                return buffer.readByteArray()
            }
        }
    }

    fun smb(streamUrl: String): EmbeddedArt.Reader = object : EmbeddedArt.Reader {
        override val size: Long = Long.MAX_VALUE

        private var file: com.hierynomus.smbj.share.File? = null
        private var path: String? = null

        private fun handle(): com.hierynomus.smbj.share.File {
            file?.let { return it }
            val share = com.bxane.nuvx.data.smb.SmbAuth.share
            val resolved = SmbConfig.relativePath(streamUrl, share)
                ?: throw java.io.IOException("Not an SMB file: $streamUrl")
            path = resolved
            return SmbClient.openFile(resolved).also { file = it }
        }

        override fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0 || offset < 0) return ByteArray(0)
            val handle = handle()
            val buf = ByteArray(length)
            var at = 0
            var pos = offset
            while (at < length) {
                val read = handle.read(buf, pos, at, length - at)
                if (read <= 0) break
                at += read
                pos += read
            }
            return if (at == length) buf else buf.copyOf(at)
        }

        override fun close() {
            runCatching { file?.close() }
            file = null
        }
    }

    suspend fun picture(url: String, isSmb: Boolean): EmbeddedArt.Picture? =
        withContext(Dispatchers.IO) {
            val reader = if (isSmb) smb(url) else http(url)
            try {
                runCatching { EmbeddedArt.extract(reader) }.getOrNull()
            } finally {
                runCatching { reader.close() }
            }
        }
}
