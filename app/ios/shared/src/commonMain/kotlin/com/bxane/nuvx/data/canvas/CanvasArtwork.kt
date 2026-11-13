package com.bxane.nuvx.data.canvas






data class CanvasArtworkDto(
    val url: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: String,
    val fallbackUrl: String? = null,
) {
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() || when (source) {


            "community" -> title.communityTitleKey() == wantTitle.communityTitleKey()
            else -> title.normalizeForMatch() == wantTitle.normalizeForMatch()
        }
        val titleArtists = splitArtists(wantArtist)
        val ourArtists = splitArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() || when (source) {


            "community" -> titleArtists.isNotEmpty() && ourArtists.isNotEmpty() &&
                titleArtists.any { want -> ourArtists.any { it == want } }
            else -> titleArtists.isNotEmpty() && ourArtists.isNotEmpty() &&
                titleArtists.all { want -> ourArtists.any { it == want } }
        }
        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForMatch() == wantAlbum.normalizeForMatch()
        return titleOk && artistOk && albumOk
    }
}


internal fun String.communityTitleKey(): String = normalizeForMatch()
    .replace(Regex("\\s+(?:feat|featuring|ft)\\s+.+$"), "")
    .trim()

internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"


internal fun String.normalizeForMatch(): String =
    lowercase()
        .replace(Regex("[àáâãäåāăą]"), "a")
        .replace(Regex("[èéêëēĕėęě]"), "e")
        .replace(Regex("[ìíîïĩīĭįı]"), "i")
        .replace(Regex("[òóôõöōŏő]"), "o")
        .replace(Regex("[ùúûüũūŭůűų]"), "u")
        .replace(Regex("[ýÿŷ]"), "y")
        .replace(Regex("[çćĉċč]"), "c")
        .replace(Regex("[ñńņň]"), "n")
        .replace("æ", "ae")
        .replace("œ", "oe")
        .replace("ß", "ss")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS)
        .map { it.normalizeForMatch() }
        .filter { it.isNotBlank() }

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)


internal fun String.cleanedForCanvas(): String = replace(CANVAS_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val CANVAS_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

internal expect fun canvasNowMs(): Long
