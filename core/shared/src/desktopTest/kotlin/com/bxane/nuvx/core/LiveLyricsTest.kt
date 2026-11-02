package com.bxane.nuvx.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in: NUVX_LIVE=1. Prints only result type and size, never lyric text. */
class LiveLyricsTest {
    @Test fun findsTimedLyricsForCommonShapes() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact LRCLIB", System.getenv("NUVX_LIVE") == "1")
        val lrclib = LrcLib(nuvxHttpClient())
        val cases = listOf(
            Track("a", "Paradise", listOf("Coldplay"), "Mylo Xyloto", 278_000),
            Track("b", "Paradise", listOf("Coldplay"), null, 278_000),
            Track("c", "Viva La Vida", listOf("Coldplay"), null, 242_000),
        )
        for (t in cases) {
            val r = lrclib.find(t, 0)
            val shape = when (r) { is Lyrics.Synced -> "Synced(${r.lines.size} lines)"; is Lyrics.Plain -> "Plain"; null -> "null"; else -> r.toString() }
            println("${t.title} album=${t.album} -> $shape")
            assertTrue(r is Lyrics.Synced, "${t.title} album=${t.album} should have timed lyrics, got $shape")
        }
    }
}
