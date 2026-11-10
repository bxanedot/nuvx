package com.bxane.nuvx.data.smb

import com.bxane.nuvx.data.model.Song
import java.util.Locale

object SmbConfig {

    const val BROWSE_ID = "local:smb"
    const val SCHEME = "smb"
    const val DEFAULT_PORT = 445

    val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm",
    )

    val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun normalizeHost(raw: String): String = splitHostPort(raw).first

    fun splitHostPort(raw: String): Pair<String, Int> {
        val authority = raw.trim().removePrefix("smb://").removePrefix("\\\\")
            .substringBefore('/').substringBefore('\\')
        if (authority.startsWith("[")) {
            val host = authority.substringAfter('[').substringBefore(']').lowercase(Locale.ROOT)
            val port = authority.substringAfter("]:", "").toIntOrNull()
            return host to validPort(port)
        }
        val port = authority.substringAfterLast(':', "").takeIf { ':' in authority }?.toIntOrNull()
        val host = authority.substringBeforeLast(':').lowercase(Locale.ROOT).trim()
        return host to validPort(port)
    }

    private fun validPort(port: Int?): Int =
        if (port != null && port in 1..65535) port else DEFAULT_PORT

    fun isConfigured(host: String, share: String): Boolean =
        normalizeHost(host).isNotBlank() && share.trim().isNotBlank()

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in audioExtensions
    }

    fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in imageExtensions
    }

    fun idFor(host: String, share: String, path: String): String =
        "smb:${normalizeHost(host)}/${share.trim().trim('/')}/${path.trim('/')}".trimEnd('/')

    fun isSmbId(videoId: String): Boolean = videoId.startsWith("smb:")

    fun isSmbUrl(url: String): Boolean = url.startsWith("smb://")

    fun relativePath(streamUrl: String, share: String): String? {
        val segments = streamUrl.removePrefix("smb://").split('/').filter { it.isNotBlank() }
        if (segments.size < 3) return null
        if (!segments[1].equals(share.trim().trim('/'), ignoreCase = true)) return null
        val path = segments.drop(2).joinToString("/").substringBefore('?').substringBefore('#')
        return path.takeIf { it.isNotBlank() }
    }

    fun songFor(host: String, share: String, path: String, albumName: String? = null): Song {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val streamUrl = "smb://${normalizeHost(host)}/${share.trim().trim('/')}/${path.trim('/')}"
        return com.bxane.nuvx.data.remote.RemoteSong.build(
            videoId = idFor(host, share, path),
            streamUrl = streamUrl,
            fileName = fileName,
            albumName = albumName,
            source = "SMB",
            browseId = BROWSE_ID,
        )
    }
}

object SmbAuth {
    @Volatile var host: String = ""
    @Volatile var port: Int = SmbConfig.DEFAULT_PORT
    @Volatile var share: String = ""
    @Volatile var basePath: String = ""
    @Volatile var username: String = ""
    @Volatile var password: String = ""

    fun update(host: String, share: String, basePath: String, username: String, password: String) {
        val (parsedHost, parsedPort) = SmbConfig.splitHostPort(host)
        this.host = parsedHost
        this.port = parsedPort
        this.share = share.trim().trim('/')
        this.basePath = basePath.trim().trim('/').replace('\\', '/')
        this.username = username.trim()
        this.password = password
        SmbConnection.invalidate()
    }

    fun isConfigured(): Boolean = SmbConfig.isConfigured(host, share)
}
