package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WordProgressTest {
    // Placeholder words only.
    private val lyrics = Lyrics.Synced(listOf(
        LyricLine(1_000, "alpha beta", listOf(LyricWord("alpha ", 1_000), LyricWord("beta", 2_000))),
        LyricLine(4_000, "gamma", listOf(LyricWord("gamma", 4_000))),
    ), wordSynced = true)

    @Test fun progressRunsFromEachWordToTheNext() {
        assertEquals(0f, lyrics.wordProgress(0, 0, 900))
        assertEquals(0.5f, lyrics.wordProgress(0, 0, 1_500))
        assertEquals(1f, lyrics.wordProgress(0, 0, 2_100))
        assertEquals(0.5f, lyrics.wordProgress(0, 1, 3_000), "last word in a line ends at the next line")
        assertEquals(0.5f, lyrics.wordProgress(1, 0, 4_300), "last word overall gets a short default")
        assertEquals(0f, lyrics.wordProgress(5, 0, 4_300), "out of range is safe")
    }
}
