package com.bxane.nuvx.data.remote

import kotlin.io.encoding.Base64























object WebDavConfig {


    const val BROWSE_ID: String = "local:webdav"


    const val ID_PREFIX: String = "webdav:"









    val audioExtensions: Set<String> = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm",
    )


    val imageExtensions: Set<String> = setOf("jpg", "jpeg", "png", "webp")











    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()


        if (trimmed.isEmpty() || trimmed.all { it == '/' }) return ""
        val lower = trimmed.lowercase()
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")




        val withoutTrailing = trimmed.trimEnd('/')
        return if (hasScheme) withoutTrailing else "https://$withoutTrailing"
    }














    fun isConfigured(url: String): Boolean {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return false
        if (normalized.any { it.isWhitespace() }) return false
        val lower = normalized.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = hostOf(normalized) ?: return false
        return host != "https" && host != "http"
    }








    fun basicAuthHeader(username: String, password: String): String? {
        if (username.isBlank() && password.isBlank()) return null
        val pair = "$username:$password"
        return "Basic " + Base64.Default.encode(pair.encodeToByteArray())
    }











    fun hostOf(url: String): String? {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return null
        val afterScheme = normalized.substringAfter("://", "")


        if (afterScheme.isEmpty() || afterScheme == normalized) return null

        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }

        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith('[')) {


            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            hostAndPort.substring(0, close + 1)
        } else {
            hostAndPort.substringBefore(':')
        }
        if (host.isBlank()) return null


        if (host.any { it.isWhitespace() }) return null
        return host.lowercase()
    }












    fun authorityOf(url: String): String? {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return null
        val afterScheme = normalized.substringAfter("://", "")
        if (afterScheme.isEmpty() || afterScheme == normalized) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith('[')) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            hostAndPort.substring(0, close + 1)
        } else {
            hostAndPort.substringBefore(':')
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val port = if (hostAndPort.startsWith('[')) {
            hostAndPort.substringAfter(']', "").trimStart(':')
        } else {
            hostAndPort.substringAfter(':', "")
        }
        return (host + if (port.isBlank()) "" else ":$port").lowercase()
    }

    fun isAudioFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in audioExtensions
    }

    fun isImageFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in imageExtensions
    }








    fun idFor(fileUrl: String): String = ID_PREFIX + fileUrl

    fun isWebDavId(videoId: String): Boolean = videoId.startsWith(ID_PREFIX)









    fun fileUrlOf(videoId: String): String? {
        if (!isWebDavId(videoId)) return null
        val url = videoId.removePrefix(ID_PREFIX)
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }









    internal fun pathOf(fileUrl: String): String {
        var path = fileUrl.trim()
        val schemeEnd = path.indexOf("://")
        if (schemeEnd >= 0) {
            path = path.substring(schemeEnd + 3)
            val slash = path.indexOf('/')
            path = if (slash >= 0) path.substring(slash) else ""
        }
        return path.substringBefore('?').substringBefore('#').trimEnd('/')
    }


    fun fileNameOf(fileUrl: String): String {
        val path = pathOf(fileUrl)
        if (path.isEmpty()) return ""
        val segment = path.substringAfterLast('/')
        return percentDecode(segment)
    }


    fun folderNameOf(fileUrl: String): String? {
        val path = pathOf(fileUrl)
        val slash = path.lastIndexOf('/')


        if (slash <= 0) return null
        val name = path.substring(0, slash).substringAfterLast('/')
        return percentDecode(name).trim().takeIf { it.isNotEmpty() }
    }


















    fun dirKeyOf(fileUrl: String): String {
        val path = pathOf(fileUrl)
        val slash = path.lastIndexOf('/')
        val directory = if (slash <= 0) "" else path.substring(0, slash)
        return authorityOf(fileUrl).orEmpty() + directory
    }









    fun uploadFileName(title: String, artist: String, extension: String): String {
        val rawBase = if (artist.isNotBlank() && artist != UNKNOWN_ARTIST) "$artist - $title" else title
        val clean = rawBase
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "track" }
        val ext = extension.lowercase().trimStart('.').takeIf { it.isNotEmpty() } ?: "mp3"
        return "$clean.$ext"
    }









    fun resolveNumberedName(base: String, extension: String, taken: Set<String>): String {
        val ext = extension.lowercase().trimStart('.').takeIf { it.isNotEmpty() } ?: "mp3"
        val lowered = taken.map { it.lowercase() }.toSet()
        var candidate = "$base.$ext"
        var n = 0
        while (candidate.lowercase() in lowered && n < 1000) {
            n += 1
            candidate = "$base ($n).$ext"
        }
        return candidate
    }








    fun joinUrl(root: String, segment: String): String {
        val encoded = Percent.encodeSegment(segment)
        return "${root.trimEnd('/')}/$encoded"
    }

    const val UNKNOWN_ARTIST: String = "Unknown Artist"

    private fun percentDecode(value: String): String = Percent.decode(value)
}
