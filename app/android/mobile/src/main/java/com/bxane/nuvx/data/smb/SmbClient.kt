package com.bxane.nuvx.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

object SmbClient {

    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    data class Listing(val audio: List<Entry>, val images: List<Entry>)

    suspend fun testConnection(
        host: String,
        share: String,
        basePath: String,
        username: String,
        password: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(SmbConfig.isConfigured(host, share)) { "Enter a server and a share" }
            SmbAuth.update(host, share, basePath, username, password)
            SmbConnection.withShare { diskShare ->
                diskShare.list(smbPath(SmbAuth.basePath))
            }
        }.map { }
    }

    suspend fun listLibrary(maxFiles: Int = 10_000): Result<Listing> = withContext(Dispatchers.IO) {
        runCatching {
            require(SmbAuth.isConfigured()) { "SMB is not configured" }
            val audio = ArrayList<Entry>()
            val images = ArrayList<Entry>()
            SmbConnection.withShare { diskShare ->
                val stack = ArrayDeque<String>()
                stack.add(SmbAuth.basePath)
                val visited = HashSet<String>()
                while (stack.isNotEmpty() && audio.size < maxFiles) {
                    val dir = stack.removeFirst()
                    if (!visited.add(dir.lowercase())) continue
                    for (child in listDir(diskShare, dir)) {
                        val childPath = if (dir.isBlank()) child.path else "$dir/${child.path}"
                        if (child.isDirectory) {
                            if (!visited.contains(childPath.lowercase())) stack.add(childPath)
                        } else {
                            val name = child.path.substringAfterLast('\\').substringAfterLast('/')
                            when {
                                SmbConfig.isAudioFile(name) ->
                                    audio.add(Entry(childPath, false, child.size))
                                SmbConfig.isImageFile(name) ->
                                    images.add(Entry(childPath, false, child.size))
                            }
                        }
                    }
                }
            }
            Listing(audio, images)
        }
    }

    fun openFile(path: String): com.hierynomus.smbj.share.File =
        SmbConnection.withShare { diskShare ->
            diskShare.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }

    fun readFully(streamUrl: String, maxBytes: Long = 16 * 1024 * 1024): ByteArray {
        val path = SmbConfig.relativePath(streamUrl, SmbAuth.share)
            ?: throw SmbConnection.SmbException("Not an SMB file: $streamUrl")
        val file = openFile(path)
        try {
            val length = runCatching { file.length }.getOrNull() ?: -1
            val out = java.io.ByteArrayOutputStream(
                length.takeIf { it > 0 }?.coerceAtMost(maxBytes)?.toInt() ?: 8192,
            )
            val chunk = ByteArray(64 * 1024)
            var offset = 0L
            while (length < 0 || offset < length) {
                val read = file.read(chunk, offset, 0, chunk.size)
                if (read <= 0) break
                offset += read
                if (offset > maxBytes) throw SmbConnection.SmbException("File too large for a cover")
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        } finally {
            runCatching { file.close() }
        }
    }

    fun smbPath(displayPath: String): String =
        displayPath.replace('/', '\\').trim('\\').ifBlank { "" }

    private fun listDir(diskShare: DiskShare, dir: String): List<Entry> {
        return diskShare.list(smbPath(dir)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            Entry(name, isDir, info.endOfFile)
        }
    }
}
