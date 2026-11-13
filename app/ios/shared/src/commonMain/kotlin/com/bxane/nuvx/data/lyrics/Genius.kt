package com.bxane.nuvx.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Genius {





    private const val USER_AGENT = "nuvx"









    suspend fun lyrics(title: String, artist: String): List<LyricLineDto>? =
        withContext(Dispatchers.Default) {
            runCatching { scrapeLyrics(title, artist) }.getOrNull()
        }

    private suspend fun scrapeLyrics(title: String, artist: String): List<LyricLineDto>? {
        val cleanTitle = cleanQuery(title)
        val cleanArtist = cleanQuery(artist)


        val parts = cleanTitle.split(TITLE_SEPARATOR, limit = 2)
            .takeIf { cleanTitle.contains(TITLE_SEPARATOR) }

        val extractedTitle = when {
            parts == null -> cleanTitle
            parts[0].trim().equals(cleanArtist, ignoreCase = true) -> parts[1].trim()
            parts[1].trim().equals(cleanArtist, ignoreCase = true) -> parts[0].trim()
            parts[0].isNotBlank() && parts[1].isNotBlank() -> parts[1].trim()
            else -> cleanTitle
        }
        val extractedArtist = when {
            parts == null -> cleanArtist
            parts[0].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
            parts[1].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
            cleanArtist.isBlank() -> parts[0].trim()
            else -> cleanArtist
        }

        val titleWithoutBrackets = extractedTitle
            .replace(BRACKETED_CONTENT, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(WHITESPACE, " ")
            .trim()

        val attempts = mutableListOf<SearchAttempt>()
        fun add(query: String, atTitle: String, atArtist: String) {
            val trimmed = query.trim()
            if (trimmed.isNotEmpty()) attempts += SearchAttempt(trimmed, atTitle, atArtist)
        }

        if (extractedArtist.isNotBlank() && extractedTitle.isNotBlank()) {
            add("$extractedArtist $extractedTitle", extractedTitle, extractedArtist)
        }
        if (extractedArtist.isNotBlank() && titleWithoutBrackets.isNotBlank() &&
            titleWithoutBrackets != extractedTitle
        ) {
            add("$extractedArtist $titleWithoutBrackets", titleWithoutBrackets, extractedArtist)
        }
        if (cleanTitle != extractedTitle) {
            add("$cleanArtist $cleanTitle", cleanTitle, cleanArtist)
            add(cleanTitle, extractedTitle, extractedArtist)
        }
        if (titleWithoutBrackets.isNotBlank()) {
            add(titleWithoutBrackets, titleWithoutBrackets, extractedArtist)
        } else if (extractedTitle.isNotBlank()) {
            add(extractedTitle, extractedTitle, extractedArtist)
        }

        val url = attempts.distinctBy { it.query }
            .firstNotNullOfOrNull { searchSongUrl(it.query, it.title, it.artist) }
            ?: return null
        val html = lyricsGet(
            url = url,
            agent = USER_AGENT,

            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            timeoutMillis = 8_000,
        ) ?: return null
        return parseHtml(html)?.takeIf { it.isNotEmpty() }
    }

    private data class SearchAttempt(val query: String, val title: String, val artist: String)







    internal suspend fun searchSongUrl(query: String, targetTitle: String, targetArtist: String): String? {
        val body = lyricsGet(
            url = SEARCH_ENDPOINT,
            query = mapOf("q" to query),


            agent = USER_AGENT,
            headers = mapOf("Accept" to "application/json"),
        ) ?: return null
        return runCatching {
            val response = lyricsJson.parseToJsonElement(body).jsonObject["response"]?.jsonObject
                ?: return null
            val sections = response["sections"]?.let { it as? kotlinx.serialization.json.JsonArray }
                ?: return null
            val songSection = sections
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "song" }
                ?: return null
            val hits = songSection["hits"].let { it as? kotlinx.serialization.json.JsonArray }
                ?: return null
            bestMatch(
                hits.mapNotNull { (it as? JsonObject)?.get("result")?.jsonObject },
                targetTitle,
                targetArtist,
            )?.get("url")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }










    internal fun bestMatch(
        candidates: List<JsonObject>,
        targetTitle: String,
        targetArtist: String,
    ): JsonObject? {
        if (candidates.isEmpty()) return null
        val normTitle = targetTitle.trim().lowercase()
        val normArtist = targetArtist.trim().lowercase()

        val scored = candidates.mapNotNull { item ->
            val title = item["title"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            val artist = item["artist_names"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            val titleMatches = normTitle.isNotBlank() &&
                (title == normTitle || title.contains(normTitle) || normTitle.contains(title))
            val artistMatches = normArtist.isNotBlank() &&
                (artist == normArtist || artist.contains(normArtist) || normArtist.contains(artist))
            if (!titleMatches && !artistMatches) return@mapNotNull null

            var score = 0
            if (title == normTitle) score += 50 else if (titleMatches) score += 25
            if (artistMatches) {
                if (artist == normArtist) score += 40 else score += 20
            }











            val path = item["path"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            if (path.contains("translation") && !normTitle.contains("translation")) score -= 30
            if (path.contains("turkce") || path.contains("türkçe") ||
                path.contains("polskie-tlumaczenie") || path.contains("polskie-tłumaczenie")
            ) score -= 40
            if (path.contains("transcription") || path.contains("transkrypcja")) score -= 20
            if (path.contains("tracklist") || path.contains("album-art")) score -= 50

            if (score <= 0) return@mapNotNull null
            item to score
        }
        return scored.maxByOrNull { it.second }?.first
    }







    internal fun parseHtml(html: String): List<LyricLineDto>? {
        val region = HtmlText.textOfFirstElement(
            html = html,
            tag = "div",
            wanted = { attributes -> CONTAINER_ATTRIBUTE.containsMatchIn(attributes) },
            skipWhen = { attributes -> EXCLUDED.any { it.containsMatchIn(attributes) } },
        ) ?: HtmlText.textOfFirstElement(
            html = html,
            tag = "div",
            wanted = { attributes -> LEGACY_CLASS.containsMatchIn(attributes) },
            skipWhen = { attributes -> EXCLUDED.any { it.containsMatchIn(attributes) } },
        )
        if (region.isNullOrBlank()) return null
        val cleaned = stripArtifacts(region)
        return textToLyricLines(cleaned).takeIf { it.isNotEmpty() }
    }








    internal fun stripArtifacts(raw: String): String = raw
        .replace('\u00A0', ' ')
        .replace('\u200B', ' ')
        .replace('\uFEFF', ' ')
        .replace(YOU_MIGHT_ALSO_LIKE, "")
        .trim()
        .replace(TRAILING_EMBED, "")
        .trim()









    internal fun textToLyricLines(text: String): List<LyricLineDto> {
        val lines = mutableListOf<LyricLineDto>()
        var lastWasGap = false
        for (raw in text.lines()) {
            val line = raw.trim().replace(TRAILING_EMBED, "").trim()
            if (line.isEmpty()) {
                if (!lastWasGap && lines.isNotEmpty()) {
                    lines += LyricLineDto(timeMs = 0L, text = "")
                    lastWasGap = true
                }
            } else {
                lines += LyricLineDto(timeMs = 0L, text = line)
                lastWasGap = false
            }
        }
        while (lines.isNotEmpty() && lines.first().isGap) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isGap) lines.removeAt(lines.lastIndex)
        return lines
    }

    private fun cleanQuery(text: String): String {
        val cleaned = text
            .replace(DECORATIVE_CHARS, " ")
            .replace(NOISE, " ")
            .replace(PRODUCER_TAGS, " ")
            .substringBefore(" | ")
            .replace(WHITESPACE, " ")
            .trim()
        return cleaned.ifBlank { text.trim() }
    }

    private const val SEARCH_ENDPOINT = "https://genius.com/api/search/multi"

    private val CONTAINER_ATTRIBUTE = Regex("""data-lyrics-container""", RegexOption.IGNORE_CASE)
    private val LEGACY_CLASS = Regex("""class\s*=\s*["'][^"']*\bLyrics\b""", RegexOption.IGNORE_CASE)







    private val EXCLUDED = listOf(
        Regex("""data-exclude-from-selection""", RegexOption.IGNORE_CASE),
        Regex("""LyricsHeader__Container"""),
        Regex("""SongBioPreview__Container"""),
        Regex("""InreadAd__Container"""),
        Regex("""^\s*class\s*=\s*["'][^"']*\b(SongBioPreview|InreadAd|LyricsHeader)\b"""),
    )

    private val WHITESPACE = Regex("""\s+""")
    private val TITLE_SEPARATOR = Regex("""\s*[-–—:]\s*""")
    private val DECORATIVE_CHARS = Regex("""[♪♫★☆【】《》「」~_]""")
    private val PRODUCER_TAGS = Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""")
    private val NOISE = Regex(
        """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|""" +
            """lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
            """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|""" +
            """full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETED_CONTENT = Regex("""\s*[\(\[].*?[\)\]]""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}\s]""")
    private val YOU_MIGHT_ALSO_LIKE = Regex("""\d*You might also like""", RegexOption.IGNORE_CASE)
    private val TRAILING_EMBED = Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE)
}
