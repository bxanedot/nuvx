package com.bxane.nuvx.data.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



























object BiniLyrics {



    private const val BASE = "https://lrc.red/api/v1"


    data class Match(val isrc: String?, val lines: List<LyricLineDto>)










    internal suspend fun identify(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
    ): Hit? {
        val query = buildMap {
            if (!isrc.isNullOrBlank()) {


                put("isrc", isrc)
            } else {
                put("track", title)
                put("artist", artist)
                if (!album.isNullOrBlank()) put("album", album)
                val seconds = durationMs / 1000
                if (seconds > 0) put("duration", seconds.toString())
            }
        }


        val body = lyricsGet(BASE, query = query) ?: return null
        val response = runCatching { lyricsJson.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: return null
        val candidates = response.results.orEmpty()
        if (!isrc.isNullOrBlank()) {




            return selectHitForIsrc(candidates, isrc, title, artist, durationMs)
        }
        return selectHit(candidates, title, artist, durationMs)
    }

    internal fun selectHitForIsrc(
        candidates: List<Hit>,
        isrc: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Hit? = candidates.firstOrNull { it.isrc.equals(isrc.trim(), ignoreCase = true) }
        ?: selectHit(candidates.filter { it.isrc.isNullOrBlank() }, title, artist, durationMs)


    internal fun selectHit(
        candidates: List<Hit>,
        title: String,
        artist: String,
        durationMs: Long,
    ): Hit? = candidates.mapNotNull { candidate ->
        val score = LyricsMatching.candidateScore(
            wantedTitle = title,
            wantedArtist = artist,
            wantedDurationMs = durationMs,
            candidateTitle = candidate.trackName,
            candidateArtist = candidate.artistName,
            candidateDurationMs = candidate.duration.toDurationMs(),
            requireArtist = true,
        ) ?: return@mapNotNull null
        candidate to score
    }.maxByOrNull { it.second }?.first

    private fun Int?.toDurationMs(): Long = when {
        this == null || this <= 0 -> 0L
        this < 10_000 -> this.toLong() * 1000L
        else -> this.toLong()
    }


    internal suspend fun lyricsFor(hit: Hit): Match? {
        val document = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return null
        val ttml = lyricsGet(document) ?: return null
        val lines = TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() } ?: return null
        return Match(hit.isrc?.takeIf { it.isNotBlank() }, lines)
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
    ): Match? = identify(title, artist, durationMs, album, isrc)?.let { lyricsFor(it) }

    @Serializable
    internal data class Response(
        val total: Int? = null,

        val source: String? = null,
        val results: List<Hit>? = null,
    )

    @Serializable
    internal data class Hit(
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int? = null,
        val isrc: String? = null,

        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )
}
