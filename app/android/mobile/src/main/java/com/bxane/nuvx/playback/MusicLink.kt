package com.bxane.nuvx.playback

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LinkRequest {
    data class Track(val videoId: String) : LinkRequest

    data class Page(val browseId: String) : LinkRequest

    data class Search(val query: String, val play: Boolean) : LinkRequest

    data object Resume : LinkRequest

    data class LocalFile(val uri: Uri) : LinkRequest
}

object MusicLink {

    private const val EXTRA_CONSUMED = "nuvx.linkConsumed"

    private val _pending = MutableStateFlow<LinkRequest?>(null)

    val pending: StateFlow<LinkRequest?> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val request = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "content" || uri.scheme == "file") LinkRequest.LocalFile(uri) else parse(uri)
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let(::firstUrl)
                ?.let { parse(Uri.parse(it)) }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY).orEmpty().trim()
                if (query.isEmpty()) LinkRequest.Resume else LinkRequest.Search(query, play = true)
            }
            else -> null
        } ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = request
        return true
    }

    fun handled() {
        _pending.value = null
    }

    fun parse(uri: Uri): LinkRequest? {
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.pathSegments.orEmpty()
        if (host == "youtu.be") {
            return segments.firstOrNull()?.let(::track)
        }
        if (host != "youtube.com" && host != "music.youtube.com" && host != "m.youtube.com") {
            return null
        }
        val list = uri.getQueryParameter("list")?.trim().orEmpty()
        return when (segments.firstOrNull()) {
            "watch" -> uri.getQueryParameter("v")?.let(::track) ?: playlist(list)
            "playlist" -> playlist(list)
            "shorts", "embed", "v" -> segments.getOrNull(1)?.let(::track)
            "channel", "browse" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?.let(LinkRequest::Page)
            "search" -> uri.getQueryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { LinkRequest.Search(it, play = false) }
            else -> list.takeIf { it.isNotEmpty() }?.let { playlist(it) }
        }
    }

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
