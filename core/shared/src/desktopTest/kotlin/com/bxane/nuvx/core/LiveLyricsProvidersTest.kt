package com.bxane.nuvx.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in: NUVX_LIVE=1. Checks only that a provider returns a recognized shape; lyric text is never logged. */
class LiveLyricsProvidersTest {
    @Test fun officialProvidersReturnARecognizedLyricsShape() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact official lyrics APIs", System.getenv("NUVX_LIVE") == "1")
        val http = nuvxHttpClient()
        try {
            val result = LyricsChain(defaultLyricsProviders(http)).find(
                Track("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"), "Whenever You Need Somebody", 213_000),
            )
            val lyrics = assertNotNull(result).lyrics
            when (lyrics) {
                is Lyrics.Synced -> assertTrue(lyrics.lines.isNotEmpty())
                is Lyrics.Plain -> assertTrue(lyrics.text.isNotBlank())
                Lyrics.Instrumental -> assertTrue(true)
            }
        } finally {
            http.close()
        }
    }
}
