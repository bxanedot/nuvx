package com.bxane.nuvx.data.lyrics


















object BetterLyrics {
    private const val BASE = "https://lyrics-api.boidu.dev/getLyrics"
    private const val PORTATO = "https://lyrics-api.boidu.dev/getLyricsPortato"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLineDto>? = fetch(BASE, title, artist, durationMs, album)


    suspend fun portato(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLineDto>? = fetch(PORTATO, title, artist, durationMs, album)

    private suspend fun fetch(
        endpoint: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLineDto>? {
        val query = buildMap {
            put("s", title)
            put("a", artist)
            val seconds = durationMs / 1000
            if (seconds > 0) put("d", seconds.toString())
            if (!album.isNullOrBlank()) put("al", album)
        }
        val body = lyricsGet(endpoint, query = query) ?: return null
        return ProviderLyrics.parse(body)
    }
}
