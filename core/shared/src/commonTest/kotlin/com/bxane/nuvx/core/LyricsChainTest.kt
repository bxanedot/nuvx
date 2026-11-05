package com.bxane.nuvx.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
        val timed = P("C") { synced.copy(wordSynced = true) }
        val never = P("D") { synced.copy(wordSynced = true) }
        assertEquals(SourcedLyrics(synced.copy(wordSynced = true), "C"), LyricsChain(listOf(plain, down, timed, never)).find(t))
        assertEquals(0, never.calls, "stops at the first timed result")
    }

    @Test fun lineSyncedLyricsBeatEarlierPlainFallback() = runTest {
        val plain = P("Plain") { Lyrics.Plain("plain") }
        val lineOnly = P("Line only") { synced }
        val expected = SourcedLyrics(synced, "Line only")

        assertEquals(expected, LyricsChain(listOf(plain, lineOnly)).find(t, preferWordSynced = true))
        assertEquals(expected, LyricsChain(listOf(plain, lineOnly)).find(t, preferWordSynced = false))
    }

    @Test fun plainFallbackAndNothing() = runTest {
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), LyricsChain(listOf(P("A") { Lyrics.Plain("text") }, P("B") { null })).find(t))
        assertNull(LyricsChain(listOf(P("A") { null }, P("B") { error("x") })).find(t))
    }

    @Test fun repeatedTrackLookupReusesValidProviderResult() = runTest {
        val provider = P("A") { Lyrics.Plain("text") }
        val chain = LyricsChain(listOf(provider))

        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), chain.find(t))
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), chain.find(t))
        assertEquals(1, provider.calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun concurrentLookupsForTheSameTrackShareOneProviderRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Lyrics?>()
        val provider = object : LyricsProvider {
            override val name = "A"
            var calls = 0
            override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
                calls++
                started.complete(Unit)
                return release.await()
            }
        }
        val chain = LyricsChain(listOf(provider))
        val first = async { chain.find(t) }
        started.await()
        val second = async { chain.find(t) }
        runCurrent()
        release.complete(Lyrics.Plain("text"))

        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), first.await())
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), second.await())
        assertEquals(1, provider.calls)
    }

    @Test fun missingLyricsAreRetriedAndSuccessfulResultIsCached() = runTest {
        var calls = 0
        val provider = P("A") { if (++calls == 1) null else Lyrics.Plain("text") }
        val chain = LyricsChain(listOf(provider))

        assertNull(chain.find(t))
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), chain.find(t))
        assertEquals(SourcedLyrics(Lyrics.Plain("text"), "A"), chain.find(t))
        assertEquals(2, calls)
    }

    @Test fun timingOffsetIsPartOfTheCacheKey() = runTest {
        val provider = object : LyricsProvider {
            override val name = "A"
            var calls = 0
            override suspend fun find(track: Track, offsetMs: Long): Lyrics {
                calls++
                return Lyrics.Synced(listOf(LyricLine(offsetMs, "x")), wordSynced = false)
            }
        }
        val chain = LyricsChain(listOf(provider))

        assertEquals(0L, (chain.find(t)?.lyrics as Lyrics.Synced).lines.single().startMs)
        assertEquals(500L, (chain.find(t, offsetMs = 500)?.lyrics as Lyrics.Synced).lines.single().startMs)
        assertEquals(2, provider.calls)
    }

    @Test fun cacheEvictsLeastRecentlyUsedResultAtItsBound() = runTest {
        var calls = 0
        val provider = P("A") { calls++; Lyrics.Plain("text") }
        val chain = LyricsChain(listOf(provider))
        val tracks = (0..128).map { Track("track-$it", "Song $it", listOf("Artist")) }

        tracks.take(128).forEach { chain.find(it) }
        chain.find(tracks.first())
        chain.find(tracks.last())

        assertEquals(129, calls)
        chain.find(tracks.first())
        chain.find(tracks[1])
        assertEquals(130, calls)
    }

    @Test fun selectedProviderWinsAndFallsBackWhenUnavailable() = runTest {
        val selected = P("Selected") { Lyrics.Plain("selected") }
        val timed = P("Other") { synced }
        assertEquals(SourcedLyrics(Lyrics.Plain("selected"), "Selected"), LyricsChain(listOf(timed, selected)).find(t, preferredSource = "Selected"))

        val missing = P("Selected") { null }
        assertEquals(SourcedLyrics(synced, "Other"), LyricsChain(listOf(missing, timed)).find(t, preferredSource = "Selected"))
    }

    @Test fun configuredOrderAndDisabledSourcesControlFallback() = runTest {
        val calls = mutableListOf<String>()
        val first = P("First") { calls += "First"; Lyrics.Plain("first") }
        val skipped = P("Skipped") { calls += "Skipped"; synced }
        val later = P("Later") { calls += "Later"; synced.copy(wordSynced = true) }

        val result = LyricsChain(listOf(first, skipped, later)).find(
            t,
            providerOrder = listOf("Later", "First", "Skipped"),
            disabledSources = setOf("Skipped"),
        )

        assertEquals(SourcedLyrics(synced.copy(wordSynced = true), "Later"), result)
        assertEquals(listOf("Later"), calls)
        assertEquals(0, skipped.calls)
    }

    @Test fun wordTimedLyricsWinWhenConfiguredAheadOfLineOnlyLyrics() = runTest {
        val lineOnly = P("Line only") { synced }
        val wordTimed = P("Word timed") { synced.copy(wordSynced = true) }

        assertEquals(
            SourcedLyrics(synced.copy(wordSynced = true), "Word timed"),
            LyricsChain(listOf(lineOnly, wordTimed)).find(t, preferWordSynced = true),
        )
        assertEquals(1, lineOnly.calls)
    }

    @Test fun wordTimedLyricsCanBeatTheSelectedLineOnlyProviderWhenPreferred() = runTest {
        val selected = P("Selected") { synced }
        val timed = P("Timed") { synced.copy(wordSynced = true) }

        assertEquals(
            SourcedLyrics(synced.copy(wordSynced = true), "Timed"),
            LyricsChain(listOf(selected, timed)).find(t, preferredSource = "Selected", preferWordSynced = true),
        )
        assertEquals(1, selected.calls)
        assertEquals(1, timed.calls)
    }

    @Test fun disablingWordTimingPreferenceReturnsTheFirstSynchronizedSource() = runTest {
        val lineOnly = P("Line only") { synced }
        val wordTimed = P("Word timed") { synced.copy(wordSynced = true) }

        assertEquals(SourcedLyrics(synced, "Line only"),
            LyricsChain(listOf(lineOnly, wordTimed)).find(t, preferWordSynced = false))
        assertEquals(0, wordTimed.calls)
    }
}
