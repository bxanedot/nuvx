package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.http.RawHttpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue















class SimpMusicLyricsTest {







    private fun track(
        durationSeconds: Int? = null,
        duration: Int? = null,
        syncedLyrics: String? = null,
        richSyncLyrics: String? = null,
        plainLyric: String? = null,
    ) = buildString {
        append("{")
        append("\"id\":\"a1b2c3\",")
        append("\"videoId\":\"fJ9rUzIMcZQ\",")
        append("\"songTitle\":\"Bohemian Rhapsody\",")
        append("\"artistName\":\"Queen\",")
        append("\"albumName\":\"\",")
        if (durationSeconds != null) append("\"durationSeconds\":$durationSeconds,")
        if (duration != null) append("\"duration\":$duration,")
        if (syncedLyrics != null) append("\"syncedLyrics\":${quote(syncedLyrics)},")
        if (richSyncLyrics != null) append("\"richSyncLyrics\":${quote(richSyncLyrics)},")
        if (plainLyric != null) append("\"plainLyric\":${quote(plainLyric)},")
        append("\"trackType\":\"VIDEO\",")
        append("\"vote\":0")
        append("}")
    }


    private fun response(vararg tracks: String) =
        """{"type":"success","success":true,"data":[${tracks.joinToString(",")}]}"""

    private fun quote(value: String) = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private val WORD_LRC = "[00:01.24] <00:01.24> Is <00:01.30> this <00:01.53> real"
    private val LINE_LRC = "[00:01.96] Is this real life\n[00:05.70] Or just fantasy"



    @Test
    fun `a track is matched by the length the service actually sends`() {
        val lines = SimpMusicLyrics.parse(
            response(track(durationSeconds = 310, richSyncLyrics = WORD_LRC)),
            durationMs = 310_000,
        )
        assertNotNull(lines, "the cut is 310s and so is the track, so it matches")
        assertEquals(1, lines.size)
        assertTrue(lines.first().words.isNotEmpty(), "the word stamps came through")
    }

    @Test
    fun `the older spelling of the length still matches`() {


        val lines = SimpMusicLyrics.parse(
            response(track(duration = 310, richSyncLyrics = WORD_LRC)),
            durationMs = 310_000,
        )
        assertNotNull(lines)
    }

    @Test
    fun `a cut of a different length is not the track`() {


        assertNull(
            SimpMusicLyrics.parse(
                response(track(durationSeconds = 200, richSyncLyrics = WORD_LRC)),
                durationMs = 310_000,
            ),
        )
    }

    @Test
    fun `the nearest cut wins when the database holds several`() {
        val lines = SimpMusicLyrics.parse(
            response(
                track(durationSeconds = 296, richSyncLyrics = "[00:02.00] a different cut"),
                track(durationSeconds = 310, richSyncLyrics = WORD_LRC),
            ),
            durationMs = 305_000,
        )
        assertNotNull(lines)
        assertTrue(lines.first().text.contains("real"), "got the wrong cut")
    }



    @Test
    fun `word timing is preferred over line timing`() {
        val lines = assertNotNull(
            SimpMusicLyrics.parse(
                response(track(durationSeconds = 310, syncedLyrics = LINE_LRC, richSyncLyrics = WORD_LRC)),
                durationMs = 310_000,
            ),
        )
        assertTrue(lines.first().words.isNotEmpty())
    }

    @Test
    fun `line timing is used when there is no word timing`() {
        val lines = assertNotNull(
            SimpMusicLyrics.parse(
                response(track(durationSeconds = 310, syncedLyrics = LINE_LRC, plainLyric = "is this real life")),
                durationMs = 310_000,
            ),
        )
        assertEquals(2, lines.size)
        assertTrue(lines.first().words.isEmpty())
    }

    @Test
    fun `words with no timing are still a lyric`() {




        val lines = assertNotNull(
            SimpMusicLyrics.parse(
                response(track(durationSeconds = 310, plainLyric = "Is this real life\n\nOr just fantasy")),
                durationMs = 310_000,
            ),
        )
        assertEquals(listOf("Is this real life", "Or just fantasy"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == 0L })
    }



    @Test
    fun `a service that says it failed is not an answer`() {
        assertNull(SimpMusicLyrics.parse("""{"type":"error","success":false,"error":"Not Found"}""", 310_000))
    }

    @Test
    fun `a third-party access refusal is surfaced as blocked rather than a miss`() {
        val blocked = assertFailsWith<SimpMusicBlockedException> {
            SimpMusicLyrics.parseResponse(
                RawHttpText(403, "{\"reason\":\"This API is only available to SimpMusic clients\"}"),
                310_000,
            )
        }
        assertEquals("Provider blocks third-party apps", blocked.message)
        assertNull(SimpMusicLyrics.parseResponse(RawHttpText(404, "not found"), 310_000))
    }

    @Test
    fun `a payload with no tracks in it is not an answer`() {
        assertNull(SimpMusicLyrics.parse("""{"type":"success","success":true,"data":[]}""", 310_000))
    }

    @Test
    fun `a body that is not this provider's is not a failure`() {


        assertNull(SimpMusicLyrics.parse("<html>503</html>", 310_000))
        assertNull(SimpMusicLyrics.parse("", 310_000))
    }
}
