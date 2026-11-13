package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.http.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs




object SimpMusicLyrics {

    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"
    private const val DURATION_TOLERANCE_SECONDS = 10
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun lyrics(videoId: String, durationMs: Long): List<LyricLineDto>? {
        if (videoId.isBlank()) return null
        val response = runCatching { Http.getRaw(BASE + videoId, timeoutMillis = 8_000) }.getOrNull()
            ?: return null
        return parseResponse(response, durationMs)
    }


    internal fun parseResponse(response: com.bxane.nuvx.data.http.RawHttpText, durationMs: Long): List<LyricLineDto>? {
        if (response.status == 403) throw SimpMusicBlockedException()
        if (response.status !in 200..299) return null
        return response.body?.let { parse(it, durationMs) }
    }








    internal fun parse(body: String, durationMs: Long): List<LyricLineDto>? {
        val response = runCatching { json.decodeFromString(Response.serializer(), body) }.getOrNull()
        ?: return null
        if (!response.success) return null
        val seconds = (durationMs / 1000).toInt()
        val track = response.data.orEmpty()
            .filter { candidate -> seconds <= 0 || abs(candidate.seconds() - seconds) <= DURATION_TOLERANCE_SECONDS }
            .minByOrNull { abs(it.seconds() - seconds) }
            ?: return null



        return track.richSyncLyrics?.takeIf { it.isNotBlank() }
            ?.let { EnhancedLrc.parse(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: track.syncedLyrics?.takeIf { it.isNotBlank() }
                ?.let { LrcLib.parseLrc(it) }
                ?.takeIf { it.isNotEmpty() }
            ?: track.plainLyric?.takeIf { it.isNotBlank() }
                ?.let { plain(it) }
    }


    private fun plain(text: String): List<LyricLineDto> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLineDto(timeMs = 0, text = it) }
        .toList()

    @Serializable
    private data class Response(
        val success: Boolean = false,
        val data: List<Track>? = null,
    )

    @Serializable
    private data class Track(










        @SerialName("durationSeconds") val durationSeconds: Int? = null,
        @SerialName("duration") val duration: Int? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,







        @SerialName("plainLyric") val plainLyric: String? = null,
    ) {
        fun seconds(): Int = durationSeconds ?: duration ?: 0
    }
}

internal class SimpMusicBlockedException : IllegalStateException("Provider blocks third-party apps")
