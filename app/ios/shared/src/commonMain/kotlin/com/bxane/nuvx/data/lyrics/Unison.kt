package com.bxane.nuvx.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable





















object Unison {

    private const val BASE = "https://unison.boidu.dev/lyrics"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLineDto>? = withContext(Dispatchers.Default) {
        val query = buildMap {
            put("song", title)
            put("artist", artist)
            if (!album.isNullOrBlank()) put("album", album)
            val seconds = durationMs / 1000
            if (seconds > 0) put("duration", seconds.toString())
        }
        val body = lyricsGet(BASE, query = query) ?: return@withContext null
        val response = runCatching {
            lyricsJson.decodeFromString(Response.serializer(), body)
        }.getOrNull() ?: return@withContext null



        if (response.success != true) return@withContext null
        val entry = response.data ?: return@withContext null
        if (LyricsMatching.candidateScore(
                wantedTitle = title,
                wantedArtist = artist,
                wantedDurationMs = durationMs,
                candidateTitle = entry.song,
                candidateArtist = entry.artist,
                requireArtist = true,
            ) == null
        ) return@withContext null
        linesOf(entry)
    }


    internal fun linesOf(data: Entry): List<LyricLineDto>? {
        val text = data.lyrics?.takeIf { it.isNotBlank() } ?: return null
        val lines = when {
            data.format.equals(TTML, ignoreCase = true) -> TtmlLyrics.parse(text)
            data.syncType.equals(PLAIN, ignoreCase = true) -> plain(text)




            else -> EnhancedLrc.parse(text).takeIf { it.isNotEmpty() } ?: LrcLib.parseLrc(text)
        }
        return lines.takeIf { it.isNotEmpty() }
    }






    private fun plain(text: String): List<LyricLineDto> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLineDto(timeMs = 0, text = it) }
        .toList()

    private const val TTML = "ttml"
    private const val PLAIN = "plain"

    @Serializable
    internal data class Response(
        val success: Boolean? = null,
        val data: Entry? = null,
    )

    @Serializable
    internal data class Entry(
        val song: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val lyrics: String? = null,

        val format: String? = null,

        val syncType: String? = null,
        val confidence: String? = null,
        val voteCount: Int? = null,
    )
}
