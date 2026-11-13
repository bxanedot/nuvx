package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.innertube.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull















object YouTubeMusicLyrics {

    suspend fun lyrics(videoId: String): List<LyricLineDto>? = withContext(Dispatchers.Default) {
        if (!isVideoId(videoId)) return@withContext null
        val next = runCatching { Innertube.next(videoId) }.getOrNull() ?: return@withContext null
        val tabs = next.objectsNamed("tabRenderer").toList()



        val endpoint = tabs
            .firstOrNull { tab -> tab.youtubeStrings().any { it.equals("Lyrics", ignoreCase = true) } }
            ?.objectsNamed("browseEndpoint")?.firstOrNull()
            ?: tabs.drop(1).firstNotNullOfOrNull {
                it.objectsNamed("browseEndpoint").firstOrNull()
            }
            ?: return@withContext null
        val browseId = (endpoint["browseId"] as? JsonPrimitive)?.contentOrNull
            ?: return@withContext null
        val params = (endpoint["params"] as? JsonPrimitive)?.contentOrNull
        val page = runCatching { Innertube.browse(browseId, params) }.getOrNull() ?: return@withContext null
        val shelf = page.objectsNamed("musicDescriptionShelfRenderer").firstOrNull()
            ?: return@withContext null
        val text = shelf["description"]?.youtubeStrings()?.joinToString("").orEmpty().trim()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLineDto(timeMs = 0L, text = it) }
            .toList()
            .takeIf { it.isNotEmpty() }
    }
}


object YouTubeTranscriptLyrics {

    suspend fun lyrics(videoId: String): List<LyricLineDto>? = withContext(Dispatchers.Default) {
        if (!isVideoId(videoId)) return@withContext null
        val response = runCatching { Innertube.transcript(videoId) }.getOrNull()
            ?: return@withContext null
        response.objectsNamed("transcriptCueRenderer").mapNotNull { cue ->
            val start = (cue["startOffsetMs"] as? JsonPrimitive)?.longOrNull
                ?: return@mapNotNull null
            val text = cue["cue"]?.youtubeStrings()?.joinToString("").orEmpty()



                .trim(' ', '\n', '♪')
            text.takeIf { it.isNotEmpty() }?.let { LyricLineDto(timeMs = start, text = it) }
        }.sortedBy { it.timeMs }.toList().takeIf { it.isNotEmpty() }
    }
}









internal fun isVideoId(value: String): Boolean = VIDEO_ID.matches(value)

private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")








private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
    when (this@objectsNamed) {
        is JsonObject -> for ((key, value) in this@objectsNamed) {
            if (key == name && value is JsonObject) yield(value)
            yieldAll(value.objectsNamed(name))
        }
        is JsonArray -> for (value in this@objectsNamed) yieldAll(value.objectsNamed(name))
        else -> Unit
    }
}












internal fun JsonElement.youtubeStrings(): List<String> = when (this) {
    is JsonPrimitive ->
        if (isString) contentOrNull?.let(::listOf).orEmpty() else emptyList()
    is JsonArray -> flatMap { it.youtubeStrings() }
    is JsonObject -> {
        val direct = (this["text"] as? JsonPrimitive)?.contentOrNull
            ?: (this["simpleText"] as? JsonPrimitive)?.contentOrNull
        direct?.let(::listOf) ?: values.flatMap { it.youtubeStrings() }
    }
}
