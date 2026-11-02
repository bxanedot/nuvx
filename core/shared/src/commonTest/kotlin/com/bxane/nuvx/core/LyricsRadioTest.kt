package com.bxane.nuvx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcTest {
    @Test fun lineSynced() {
        val l = Lrc.parse("[ar:x]\n[00:01.50]Hello\n[00:03.00][00:10.00]Chorus\nnot a lyric")!!
        assertEquals(listOf(1500L, 3000L, 10000L), l.lines.map { it.startMs })
        assertEquals("Chorus", l.lines.last().text)
        assertFalse(l.wordSynced)
        assertEquals(-1, l.lineAt(1000))
        assertEquals(1, l.lineAt(5000))
    }

    @Test fun enhancedWordTimingAndOffsets() {
        val l = Lrc.parse("[offset:+100]\n[00:02.00]<00:02.00>Hey <00:02.50>there", offsetMs = -50)!!
        assertTrue(l.wordSynced)
        assertEquals("Hey there", l.lines.single().text)
        assertEquals(listOf(LyricWord("Hey ", 2050), LyricWord("there", 2550)), l.lines.single().words)
    }

    @Test fun nonLrcIsNull() = assertNull(Lrc.parse("just text"))

    @Test fun titleCleaningForLookup() {
        assertEquals("One More Time", LrcLib.cleanTitle("One More Time / Aerodynamic"))
        assertEquals("Song", LrcLib.cleanTitle("Song (Official Music Video) [HQ]"))
        assertEquals("Song (feat. X)", LrcLib.cleanTitle("Song (feat. X) [Lyric Video]"))
        assertEquals("(untitled)", LrcLib.cleanTitle("(untitled)"))
    }
}

class RadioStationTest {
    private class FakePlayer : NuvxPlayer {
        override val state = MutableStateFlow(PlayerState())
        override fun playQueue(tracks: List<Track>, startIndex: Int) { state.value = PlayerState(tracks, startIndex) }
        override fun enqueue(tracks: List<Track>) { state.value = state.value.copy(queue = state.value.queue + tracks) }
        override fun togglePlay() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun skipTo(index: Int) = Unit
    }

    private class Station : MusicBackend {
        override val id = BackendId.INNERTUBEX
        override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
        override suspend fun stream(videoId: String): AudioStream? = null
        override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
            // Page 2 repeats a track from page 1 and the seed; both must be dropped.
            return if (continuation == null) RadioPage(listOf(t("seed"), t("a"), t("b")), "c1")
            else RadioPage(listOf(t("b"), t("seed"), t("c"), t("bad")), null)
        }
    }

    @Test fun extendsWithoutRepeatsOrDislikes() = runTest {
        val player = FakePlayer()
        val radio = RadioStation(BackendRouter(mapOf(BackendId.INNERTUBEX to Station())), player, this, refillWhenRemaining = 5)
        radio.start(t("seed"))
        assertEquals(listOf("seed", "a", "b"), player.state.value.queue.map { it.videoId })
        radio.dislike(t("bad"))
        radio.onState(player.state.value)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("seed", "a", "b", "c"), player.state.value.queue.map { it.videoId })
    }

    private companion object { fun t(id: String) = Track(id, id) }
}
