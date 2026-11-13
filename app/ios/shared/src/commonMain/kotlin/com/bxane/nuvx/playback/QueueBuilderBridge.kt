package com.bxane.nuvx.playback

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.innertube.Innertube
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json





object QueueBuilderBridge {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val songs = ListSerializer(Song.serializer())
    private val sessionIds = mutableSetOf<String>()
    private var generation = Innertube.sessionGeneration

    private fun checkSession() {
        val current = Innertube.sessionGeneration
        if (generation != current) {
            sessionIds.clear()
            generation = current
        }
    }

    fun rememberPlayed(videoId: String) {
        checkSession()
        if (videoId.isNotBlank()) sessionIds += videoId
    }

    fun clearSession() {
        sessionIds.clear()
    }

    fun extendJson(existingJson: String, candidatesJson: String, limit: Int): String {
        checkSession()
        val existing = runCatching { json.decodeFromString(songs, existingJson) }.getOrDefault(emptyList())
        val candidates = runCatching { json.decodeFromString(songs, candidatesJson) }.getOrDefault(emptyList())
        val skip = if (AppSettings.dontRepeatSuggestions.value) sessionIds else emptySet()
        val filtered = if (skip.isEmpty()) {
            candidates
        } else {
            candidates.filterNot { it.videoId in skip }
        }
        val extra = QueueBuilder.extend(existing, filtered, limit)


        return json.encodeToString(songs, extra)
    }
}
