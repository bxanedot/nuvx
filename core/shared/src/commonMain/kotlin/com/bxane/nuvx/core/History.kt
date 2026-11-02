package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** On-device listening and search history (never uploaded). Most recent first, de-duplicated, capped. */
class History(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val trackList = ListSerializer(Track.serializer())
    private val stringList = ListSerializer(String.serializer())

    private val _played = MutableStateFlow(load("history.played", trackList))
    val played: StateFlow<List<Track>> = _played.asStateFlow()
    private val _liked = MutableStateFlow(load("history.liked", trackList))
    /** On-device likes, newest first. */
    val liked: StateFlow<List<Track>> = _liked.asStateFlow()
    private val _searches = MutableStateFlow(load("history.searches", stringList))
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    fun played(track: Track) {
        if (_played.value.firstOrNull()?.videoId == track.videoId) return
        _played.value = (listOf(track) + _played.value.filter { it.videoId != track.videoId }).take(MAX_TRACKS)
        store.put("history.played", json.encodeToString(trackList, _played.value))
    }

    fun isLiked(videoId: String) = _liked.value.any { it.videoId == videoId }

    fun toggleLike(track: Track) {
        _liked.value = if (isLiked(track.videoId)) _liked.value.filter { it.videoId != track.videoId } else listOf(track) + _liked.value
        store.put("history.liked", json.encodeToString(trackList, _liked.value))
    }

    fun searched(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        saveSearches(listOf(q) + _searches.value.filter { !it.equals(q, ignoreCase = true) })
    }

    fun forgetSearch(query: String) = saveSearches(_searches.value - query)

    fun clearSearches() = saveSearches(emptyList())

    private fun saveSearches(list: List<String>) {
        _searches.value = list.take(MAX_SEARCHES)
        store.put("history.searches", json.encodeToString(stringList, _searches.value))
    }

    // A corrupt or older-format entry is dropped rather than crashing startup.
    private fun <T> load(key: String, s: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        store.get(key)?.let { runCatching { json.decodeFromString(s, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val MAX_TRACKS = 50
        const val MAX_SEARCHES = 20
    }
}
