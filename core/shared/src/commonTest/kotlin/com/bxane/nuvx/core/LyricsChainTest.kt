package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsChainTest {
    private class P(override val name: String, val result: () -> Lyrics?) : LyricsProvider {
        var calls = 0
        override suspend fun find(track: Track, offsetMs: Long): Lyrics? { calls++; return result() }
    }
    private val t = Track("id", "Song", listOf("Artist"))
    private val synced = Lyrics.Synced(listOf(LyricLine(0, "x")), wordSynced = false)

    @Test fun timedLyricsBeatEarlierPlainText() = runTest {
        val plain = P("A") { Lyrics.Plain("text") }
        val down = P("B") { error("offline") }
        val timed = P("C") { synced }
        val never = P("D") { synced }
        assertEquals(SourcedLyrics(synced, "C"), LyricsChain(listOf(plain, down, timed, never)).find(t))
        assertEquals(0, never.calls, "stops at the first timed result")
    }

    @Test fun plainFallbackAndNothing() = runTest {
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), LyricsChain(listOf(P("A") { Lyrics.Plain("text") }, P("B") { null })).find(t))
        assertNull(LyricsChain(listOf(P("A") { null }, P("B") { error("x") })).find(t))
    }
}
