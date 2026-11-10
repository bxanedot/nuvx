package com.bxane.nuvx.data.webdav

import com.bxane.nuvx.data.model.Song
import java.util.Locale

object WebDavConfig {

    const val BROWSE_ID = "local:webdav"

    val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm",
    )

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun isConfigured(url: String): Boolean {
        val normalized = normalizeUrl(url)
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return hostOf(normalized) != null
    }

    fun basicAuthHeader(username: String, password: String): String? {
        if (username.isBlank() && password.isBlank()) return null
        val credentials = "$username:$password"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(credentials.toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }

    fun hostOf(url: String): String? = runCatching {
        java.net.URI(normalizeUrl(url)).host?.lowercase(Locale.ROOT)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in audioExtensions
    }

    fun idFor(fileUrl: String): String = "webdav:$fileUrl"

    fun isWebDavId(videoId: String): Boolean = videoId.startsWith("webdav:")

    fun fileUrlOf(videoId: String): String? =
        videoId.removePrefix("webdav:").takeIf { isWebDavId(videoId) && it.startsWith("http") }

    fun songFor(fileUrl: String, albumName: String? = null): Song {
        val decoded = runCatching {
            java.net.URLDecoder.decode(fileUrl.substringAfterLast('/'), "UTF-8")
        }.getOrDefault(fileUrl.substringAfterLast('/'))
        return com.bxane.nuvx.data.remote.RemoteSong.build(
            videoId = idFor(fileUrl),
            streamUrl = fileUrl,
            fileName = decoded,
            albumName = albumName,
            source = "WebDAV",
            browseId = BROWSE_ID,
        )
    }
}

fun WebDavAuth.update(url: String, username: String, password: String) =
    update(WebDavConfig.hostOf(url), WebDavConfig.basicAuthHeader(username, password))
