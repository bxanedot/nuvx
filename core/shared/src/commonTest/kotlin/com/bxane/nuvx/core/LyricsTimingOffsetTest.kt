package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTimingOffsetTest {
    private class Store : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun storesBoundedOffsetsSeparatelyForSongAndPairedVideo() {
        val store = Store()
        val song = Track("song-track", "Song")
        val video = song.copy(mediaKind = MediaKind.VIDEO, pairedVideoId = "music-video")

        assertEquals(0L, LyricsTimingOffset.read(store, song))
        assertEquals(30_000L, LyricsTimingOffset.write(store, video, 40_000L))
        assertEquals(-5_000L, LyricsTimingOffset.write(store, song, -5_000L))
        assertEquals(30_000L, LyricsTimingOffset.read(store, video))
        assertEquals(-5_000L, LyricsTimingOffset.read(store, song))
        assertEquals(0L, LyricsTimingOffset.write(store, video, 0L))
        assertEquals(0L, LyricsTimingOffset.read(store, video))
        store.put("lyricsTimingOffset.music-video", "bad-value")
        assertEquals(0L, LyricsTimingOffset.read(store, video))
    }

    @Test
    fun shiftsLineAndWordTimesWithoutChangingTheSourceLyrics() {
        val source = Lyrics.Synced(
            listOf(LyricLine(1_000L, "Hello world", listOf(LyricWord("Hello", 1_000L), LyricWord("world", 1_450L)))),
            wordSynced = true,
        )

        val shifted = LyricsTimingOffset.shift(source, -250L)

        assertEquals(750L, shifted.lines.single().startMs)
        assertEquals(listOf(750L, 1_200L), shifted.lines.single().words.map(LyricWord::startMs))
        assertEquals(1_000L, source.lines.single().startMs)
        assertEquals(true, shifted.wordSynced)
    }

    @Test
    fun shiftingNearLongBoundsSaturatesRatherThanWrapping() {
        val source = Lyrics.Synced(
            listOf(LyricLine(Long.MAX_VALUE - 1, "last", listOf(LyricWord("last", Long.MIN_VALUE + 1)))),
            wordSynced = true,
        )

        val shifted = LyricsTimingOffset.shift(source, LyricsTimingOffset.MAX_OFFSET_MS)

        assertEquals(Long.MAX_VALUE, shifted.lines.single().startMs)
        assertEquals(Long.MIN_VALUE + LyricsTimingOffset.MAX_OFFSET_MS + 1, shifted.lines.single().words.single().startMs)

        val negative = LyricsTimingOffset.shift(
            Lyrics.Synced(listOf(LyricLine(Long.MIN_VALUE + 1, "first")), wordSynced = false),
            -LyricsTimingOffset.MAX_OFFSET_MS,
        )
        assertEquals(Long.MIN_VALUE, negative.lines.single().startMs)
    }
}
