package com.bxane.nuvx.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext























object Megalobiz {

    private const val BASE = "https://www.megalobiz.com"

    suspend fun lyrics(title: String, artist: String): List<LyricLineDto>? =
        withContext(Dispatchers.Default) {
            val results = lyricsGet("$BASE/searchall", query = mapOf("qry" to "$artist $title".trim()))
                ?: return@withContext null
            val path = LRC_LINK.find(results)?.groupValues?.get(1) ?: return@withContext null
            val page = lyricsGet(BASE + path.replace("&amp;", "&")) ?: return@withContext null
            val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: return@withContext null
            val lrc = raw.replace(BREAKS, "\n")
                .replace(TAG, "")
                .let(EnhancedLrc::decodeEntities)
            LrcLib.parseLrc(lrc).takeIf { lines -> lines.any { it.text.isNotBlank() } }
        }


    private val LRC_LINK =
        Regex("""href=["'](/lrc/maker/download/[^"']+)["']""", RegexOption.IGNORE_CASE)


    private val LRC_BODY = Regex(
        """id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val BREAKS = Regex("""(?i)<br\s*/?>""")
    private val TAG = Regex("""<[^>]+>""")
}
