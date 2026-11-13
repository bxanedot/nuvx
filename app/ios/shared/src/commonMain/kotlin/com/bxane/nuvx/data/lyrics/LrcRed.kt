package com.bxane.nuvx.data.lyrics

import kotlinx.serialization.Serializable
import kotlin.math.abs

















object LrcRed {

    private const val BASE = "https://lrc.red/"


    private const val DURATION_TOLERANCE_SECONDS = 3.0


    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase().takeIf { ISRC.matches(it) }?.let { "${BASE}s/$it.ttml" }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        isrc: String? = null,
    ): List<LyricLineDto>? {
        val known = isrc?.let(::documentUrl)
        if (known != null) document(known)?.let { return it }


        val hit = search(title, artist, durationMs) ?: return null
        val found = hit.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return null
        return document(found)
    }

    private suspend fun document(url: String): List<LyricLineDto>? =
        lyricsGet(url, headers = mapOf("Accept" to "application/ttml+xml, text/xml, */*"))
            ?.let(TtmlLyrics::parse)
            ?.takeIf { it.isNotEmpty() }

    private suspend fun search(title: String, artist: String, durationMs: Long): Hit? {
        val body = lyricsGet("${BASE}search.json", query = mapOf("q" to "$title $artist".trim()))
            ?: return null
        val hits = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()?.hits
            ?: return null
        return best(hits, title, artist, durationMs)
    }







    fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = durationMs / 1000.0

        fun distance(hit: Hit): Double =
            if (durationMs > 0 && hit.duration != null) abs(hit.duration - seconds) else 0.0

        return hits
            .filter { hit ->
                val name = hit.title ?: return@filter false
                hit.isrc?.let(::documentUrl) != null &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artist.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SECONDS
            }
            .minByOrNull(::distance)
    }


    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - ")).ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras = BRACKETED.findAll(title).joinToString(" ") { it.value } +
            " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()


    private fun normalized(value: String): String =
        value.lowercase()
            .map { FOLD[it] ?: it }
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private val FOLD: Map<Char, Char> = buildMap {
        "àáâãäåā".forEach { put(it, 'a') }
        "èéêëē".forEach { put(it, 'e') }
        "ìíîïī".forEach { put(it, 'i') }
        "òóôõöøō".forEach { put(it, 'o') }
        "ùúûüū".forEach { put(it, 'u') }
        "ýÿ".forEach { put(it, 'y') }
        put('ç', 'c'); put('ñ', 'n')
    }

    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
        "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
    )

    @Serializable
    data class Response(val hits: List<Hit>? = null, val next: String? = null)

    @Serializable
    data class Hit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: Int? = null,

        val duration: Double? = null,
    )
}
