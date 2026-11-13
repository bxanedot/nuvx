package com.bxane.nuvx.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow









sealed interface LinkRequest {

    data class Track(val videoId: String) : LinkRequest


    data class Page(val browseId: String) : LinkRequest





    data class Search(val query: String, val play: Boolean) : LinkRequest


    data object Resume : LinkRequest
}

object MusicLink {

    private val _pending = MutableStateFlow<LinkRequest?>(null)


    val pending: StateFlow<LinkRequest?> = _pending.asStateFlow()


    fun submitUrl(url: String?): Boolean {
        val request = url?.let { parseUrlString(it) } ?: return false
        _pending.value = request
        return true
    }


    fun submitSharedText(text: String?): Boolean {
        val request = text?.let(::firstUrl)?.let(::parseUrlString) ?: return false
        _pending.value = request
        return true
    }


    fun submitVoiceQuery(query: String): Boolean {
        val trimmed = query.trim()
        val request =
            if (trimmed.isEmpty()) LinkRequest.Resume else LinkRequest.Search(trimmed, play = true)
        _pending.value = request
        return true
    }





    fun handled() {
        _pending.value = null
    }




    fun takePendingJson(): String? {
        val req = _pending.value ?: return null
        _pending.value = null
        return when (req) {
            is LinkRequest.Track -> """{"kind":"track","id":${jsonQuote(req.videoId)}}"""
            is LinkRequest.Page -> """{"kind":"page","id":${jsonQuote(req.browseId)}}"""
            is LinkRequest.Search ->
                """{"kind":"search","query":${jsonQuote(req.query)},"play":${req.play}}"""
            LinkRequest.Resume -> """{"kind":"resume"}"""
        }
    }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""







    fun parseUrlString(raw: String): LinkRequest? {
        val uri = raw.trim()
        if (uri.isEmpty()) return null
        val hostPart = uri
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .lowercase()
            .removePrefix("www.")
        val path = uri
            .removePrefix("https://")
            .removePrefix("http://")
            .substringAfter('/')
            .substringBefore('?')
        val query = uri.substringAfter('?', "")
        val segments = path.split('/').filter { it.isNotEmpty() }

        if (hostPart == "youtu.be") {
            return segments.firstOrNull()?.let(::track)
        }
        if (hostPart != "youtube.com" && hostPart != "music.youtube.com" && hostPart != "m.youtube.com") {
            return null
        }
        val list = queryParameter(query, "list")?.trim().orEmpty()
        return when (segments.firstOrNull()) {
            "watch" -> queryParameter(query, "v")?.let(::track) ?: playlist(list)
            "playlist" -> playlist(list)
            "shorts", "embed", "v" -> segments.getOrNull(1)?.let(::track)
            "channel", "browse" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?.let(LinkRequest::Page)
            "search" -> queryParameter(query, "q")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { LinkRequest.Search(it, play = false) }
            else -> list.takeIf { it.isNotEmpty() }?.let { playlist(it) }
        }
    }

    private fun queryParameter(query: String, name: String): String? =
        query.split('&')
            .mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }
            .firstOrNull { (key, _) -> key == name }
            ?.second
            ?.let(::percentDecode)


    private fun percentDecode(raw: String): String {
        if ('%' !in raw && '+' !in raw) return raw
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '+' -> { bytes.add(' '.code.toByte()); i++ }
                '%' -> {
                    if (i + 2 < raw.length + 1 && i + 2 <= raw.length - 1 + 1) {
                        val hex = raw.substringOrNull(i + 1, i + 3)
                        val value = hex?.toIntOrNull(16)
                        if (value != null) {
                            bytes.add(value.toByte())
                            i += 3
                        } else {
                            bytes.add(c.code.toByte()); i++
                        }
                    } else {
                        bytes.add(c.code.toByte()); i++
                    }
                }
                else -> { bytes.add(c.code.toByte()); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        if (start in indices && end <= length && start < end) substring(start, end) else null

    private fun track(videoId: String): LinkRequest.Track? =
        videoId.trim().takeIf { it.isNotEmpty() }?.let(LinkRequest::Track)






    private fun playlist(listId: String): LinkRequest.Page? {
        if (listId.isEmpty()) return null
        return LinkRequest.Page(if (listId.startsWith("VL")) listId else "VL$listId")
    }


    private fun firstUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value

    private val URL_IN_TEXT = Regex("""https?://\S+""")
}
