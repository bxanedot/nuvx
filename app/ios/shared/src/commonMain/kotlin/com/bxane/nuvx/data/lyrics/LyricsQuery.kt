package com.bxane.nuvx.data.lyrics
























internal fun String.forLyricsSearch(artist: String? = null): String {
    var name = this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    name = name.replace(WHITESPACE, " ").trim().trimEnd(',', '-', '\u2013', '\u2014').trim()


        .ifBlank { trim() }






    val cleanedArtist = artist?.artistForLyricsSearch()
    val credits = cleanedArtist?.split(ARTIST_PREFIX_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    val prefixCandidates = buildList {
        cleanedArtist?.takeIf { it.isNotBlank() }?.let(::add)
        for (count in 1..credits.size) {
            val prefix = credits.take(count)
            add(prefix.joinToString(" & "))
            add(prefix.joinToString(" "))
        }
    }.distinct().sortedByDescending { it.length }

    for (prefix in prefixCandidates) {
        if (name.length <= prefix.length || !name.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            continue
        }
        val boundary = name[prefix.length]
        val separatedByPunctuation = boundary in TITLE_PREFIX_SEPARATORS



        val separatedByWords = boundary.isWhitespace() && prefix.any { it.isWhitespace() }
        if (!separatedByPunctuation && !separatedByWords) continue
        val remainder = name.substring(prefix.length).trimStart()
            .trimStart('-', '\u2013', '\u2014', ':', '|', '\u00b7')
            .trim()
        if (remainder.isNotEmpty()) return remainder
    }
    return name
}







internal fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic").trim().ifBlank { trim() }


internal fun String.isUsableLyricsQuery(): Boolean = isNotBlank() && length >= MIN_QUERY








private const val MIN_QUERY = 1

private val WHITESPACE = Regex("""\s+""")
private val ARTIST_PREFIX_SEPARATOR = Regex(
    """(?i)\s*(?:,|&|;|feat(?:uring)?\.?|ft\.?|with|x|·)\s*""",
)
private val TITLE_PREFIX_SEPARATORS = setOf('-', '\u2013', '\u2014', ':', '|', '\u00b7')










private const val OPEN_BRACKET = """[(\[（【「『〈《〔]"""
private const val CLOSE_BRACKET = """[)\]）】」』〉》〕]"""









private const val UPLOAD_LABELS =
    """(歌詞|歌词|フル|字幕|中日字幕|中文字幕|完整版|高音質|高音质|動画|PV|MV)"""

private val CREDITS = listOf(

    Regex(
        """\s*$OPEN_BRACKET\s*(feat|ft|featuring|with)\b[^)\]）】」』〉》〕]*$CLOSE_BRACKET""",
        RegexOption.IGNORE_CASE,
    ),

    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),

    Regex(
        """\s*$OPEN_BRACKET\s*(official\s*)?(music\s*)?""" +
            """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)""" +
            """\s*$CLOSE_BRACKET""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s*$OPEN_BRACKET\s*official\s*$CLOSE_BRACKET""", RegexOption.IGNORE_CASE),

    Regex("""\s*$OPEN_BRACKET\s*$UPLOAD_LABELS\s*$CLOSE_BRACKET""", RegexOption.IGNORE_CASE),



    Regex("""\s+$UPLOAD_LABELS\s*${'$'}""", RegexOption.IGNORE_CASE),
)
