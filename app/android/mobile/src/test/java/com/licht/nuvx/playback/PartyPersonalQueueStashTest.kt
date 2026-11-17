package com.licht.nuvx.playback

import com.licht.nuvx.FakeSharedPreferences
import com.licht.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PartyPersonalQueueStashTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        PartyPersonalQueueStash.initForTesting(fakePrefs)
    }

    @Test
    fun testStashAndLoad() {
        assertFalse(PartyPersonalQueueStash.hasStash())
        assertNull(PartyPersonalQueueStash.load())

        val songs = listOf(
            Song(videoId = "vid1", title = "Song 1", artist = "Artist 1", thumbnailUrl = "url1"),
            Song(videoId = "vid2", title = "Song 2", artist = "Artist 2", thumbnailUrl = "url2"),
            Song(videoId = "vid3", title = "Song 3", artist = "Artist 3", thumbnailUrl = "url3"),
        )

        PartyPersonalQueueStash.stash(
            songs = songs,
            index = 1,
            positionMs = 45000L,
            wasPlaying = true,
        )

        assertTrue(PartyPersonalQueueStash.hasStash())
        val snapshot = PartyPersonalQueueStash.load()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.songs.size)
        assertEquals("vid1", snapshot.songs[0].videoId)
        assertEquals("vid2", snapshot.songs[1].videoId)
        assertEquals("vid3", snapshot.songs[2].videoId)
        assertEquals(1, snapshot.index)
        assertEquals(45000L, snapshot.positionMs)
        assertTrue(snapshot.wasPlaying)
    }

    @Test
    fun testClearRemovesStash() {
        val songs = listOf(
            Song(videoId = "vid1", title = "Song 1", artist = "Artist 1", thumbnailUrl = null),
        )
        PartyPersonalQueueStash.stash(songs, index = 0, positionMs = 1200L, wasPlaying = false)
        assertTrue(PartyPersonalQueueStash.hasStash())

        PartyPersonalQueueStash.clear()
        assertFalse(PartyPersonalQueueStash.hasStash())
        assertNull(PartyPersonalQueueStash.load())
    }

    @Test
    fun testEmptySongsDoesNotStash() {
        PartyPersonalQueueStash.stash(emptyList(), index = 0, positionMs = 0L, wasPlaying = false)
        assertFalse(PartyPersonalQueueStash.hasStash())
        assertNull(PartyPersonalQueueStash.load())
    }

    @Test
    fun testIndexClamping() {
        val songs = listOf(
            Song(videoId = "vid1", title = "Song 1", artist = "Artist 1", thumbnailUrl = null),
            Song(videoId = "vid2", title = "Song 2", artist = "Artist 2", thumbnailUrl = null),
        )
        PartyPersonalQueueStash.stash(songs, index = 99, positionMs = 0L, wasPlaying = false)
        val snapshot = PartyPersonalQueueStash.load()
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.index)
    }

    @Test
    fun testMidAlbumPartyEntryStashesFullContextAndRestoresOnExit() {
        val albumSongs = List(15) { i ->
            Song(
                videoId = "track-$i",
                title = "Song $i",
                artist = "Artist",
                thumbnailUrl = null,
                albumName = "Album 1",
            )
        }

        PartyPersonalQueueStash.stash(
            songs = albumSongs,
            index = 4,
            positionMs = 30000L,
            wasPlaying = true,
        )

        val restored = PartyPersonalQueueStash.load()
        assertNotNull(restored)
        assertEquals(15, restored!!.songs.size)
        assertEquals(4, restored.index)
        assertEquals("track-4", restored.songs[restored.index].videoId)
        assertEquals(30000L, restored.positionMs)
        assertTrue(restored.wasPlaying)

        PartyPersonalQueueStash.clear()
        assertFalse(PartyPersonalQueueStash.hasStash())
    }

    @Test
    fun testJoinerPersonalQueueStashedAndRestoredOnExit() {
        val joinerPersonalQueue = listOf(
            Song(videoId = "personal-1", title = "P1", artist = "Artist", thumbnailUrl = null),
            Song(videoId = "personal-2", title = "P2", artist = "Artist", thumbnailUrl = null),
        )

        PartyPersonalQueueStash.stash(
            songs = joinerPersonalQueue,
            index = 0,
            positionMs = 15000L,
            wasPlaying = false,
        )

        val restored = PartyPersonalQueueStash.load()
        assertNotNull(restored)
        assertEquals(2, restored!!.songs.size)
        assertEquals("personal-1", restored.songs[0].videoId)
        assertEquals(15000L, restored.positionMs)
        assertFalse(restored.wasPlaying)
    }

}
