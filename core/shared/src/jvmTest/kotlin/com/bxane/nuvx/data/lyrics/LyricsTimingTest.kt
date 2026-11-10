package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertTrue

class LyricsTimingTest {
    private fun result(vararg lines: LyricLine) = LyricsRepository.Result(LyricsSource.LRCLIB, lines.toList())

    @Test
    fun syllableBeatsWordBeatsLineBeatsPlain() {
        val split = LyricWord(0, 500, "enough", listOf(LyricSyllable(0, 200, 0, 1), LyricSyllable(200, 500, 1, 6)))
        val syllable = result(LyricLine(0, "enough", listOf(split)))
        val word = result(LyricLine(0, "hi", listOf(LyricWord(0, 300, "hi"))))
        val line = result(LyricLine(1000, "hi"))
        val plain = result(LyricLine(0, "hi"))
        val t = LyricsRepository::timing
        assertTrue(t(syllable) < t(word) && t(word) < t(line) && t(line) < t(plain))
    }
}
