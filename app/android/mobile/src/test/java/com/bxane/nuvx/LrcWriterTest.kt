package com.bxane.nuvx

import com.bxane.nuvx.data.lyrics.LrcLib
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricWord
import com.bxane.nuvx.data.lyrics.toLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcWriterTest {

    @Test
    fun `each line is stamped as mm colon ss dot centiseconds`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "first line"),
            LyricLine(timeMs = 61_230L, text = "second line"),
        )
        assertEquals("[00:00.00]first line\n[01:01.23]second line", lines.toLrc())
    }

    @Test
    fun `milliseconds are truncated to centiseconds rather than rounded up past a second`() {
        assertEquals("[00:59.99]x", listOf(LyricLine(59_999L, "x")).toLrc())
    }

    @Test
    fun `stamps are ascii digits regardless of the default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val written = listOf(LyricLine(75_400L, "x")).toLrc()
            assertEquals("[01:15.40]x", written)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a stamp past ninety-nine minutes overflows to three digits rather than wrapping`() {
        assertEquals("[100:00.00]x", listOf(LyricLine(6_000_000L, "x")).toLrc())
    }

    @Test
    fun `an instrumental gap is written as a bare stamp`() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "first line"),
            LyricLine(timeMs = 8_000L, text = ""),
            LyricLine(timeMs = 20_000L, text = "second line"),
        )
        assertEquals("[00:01.00]first line\n[00:08.00]\n[00:20.00]second line", lines.toLrc())
    }

    @Test
    fun `lines are sorted by their stamp, whatever order they arrive in`() {
        val lines = listOf(
            LyricLine(timeMs = 5_000L, text = "later"),
            LyricLine(timeMs = 1_000L, text = "earlier"),
        )
        assertEquals("[00:01.00]earlier\n[00:05.00]later", lines.toLrc())
    }

    @Test
    fun `word timings are dropped and the line's own text is kept whole`() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(startMs = 1_000L, endMs = 1_400L, text = "two"),
                    LyricWord(startMs = 1_400L, endMs = 2_000L, text = "words"),
                ),
            ),
        )
        val written = lines.toLrc()
        assertEquals("[00:01.00]two words", written)
        assertTrue("no word stamps", '<' !in written)
    }

    @Test
    fun `an answering vocal is written back onto the end of its lead`() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "the lead line",
                background = LyricLine(timeMs = 1_600L, text = "(the answer)"),
            ),
        )
        assertEquals("[00:01.00]the lead line (the answer)", lines.toLrc())
    }

    @Test
    fun `no lines is empty text rather than a blank stamp`() {
        assertEquals("", emptyList<LyricLine>().toLrc())
    }

    @Test
    fun `what it writes, the parser reads back unchanged`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = ""),
            LyricLine(timeMs = 6_120L, text = "first line"),
            LyricLine(timeMs = 12_340L, text = "second line"),
            LyricLine(timeMs = 18_000L, text = ""),
            LyricLine(timeMs = 25_500L, text = "third line"),
        )

        val reparsed = LrcLib.parseLrc(lines.toLrc())

        assertEquals(lines.map { it.timeMs }, reparsed.map { it.timeMs })
        assertEquals(lines.map { it.text }, reparsed.map { it.text })
    }
}
