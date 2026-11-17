package com.licht.nuvx.playback

import com.licht.nuvx.FakeSharedPreferences
import com.licht.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LastPlayedTest {

    private val queue = List(200) { Song(videoId = "t$it", title = "T$it", artist = "A", thumbnailUrl = null) }

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        LastPlayed.initForTesting(prefs)
    }

    // Mirrors PlaybackQueuePersistenceController: a timeline save, then only position saves per track.
    private fun playThrough(to: Int, inParty: Boolean = false) {
        val start = LastPlayed.window(queue.size, 0)
        LastPlayed.saveQueue(start.map(queue::get), 0)
        var savedStart = start.first
        for (index in 1..to) {
            savedStart = LastPlayed.savePlaybackState(queue.size, index, savedStart, index * 1000L, inParty, queue::get)
        }
    }

    @Test
    fun `playing past the saved window still restores the playing track after a kill`() {
        playThrough(to = 120)

        val restored = LastPlayed.load()!!
        assertEquals("t120", restored.songs[restored.index].videoId)
        assertEquals(120_000L, restored.positionMs)
        assertEquals("t${120 - MAX_QUEUE_HISTORY}", restored.songs.first().videoId)
    }

    @Test
    fun `short advances keep the stored window and restore the right track`() {
        playThrough(to = 10)

        val restored = LastPlayed.load()!!
        assertEquals("t0", restored.songs.first().videoId)
        assertEquals("t10", restored.songs[restored.index].videoId)
        assertEquals(10_000L, restored.positionMs)
    }

    @Test
    fun `party playback leaves the stored personal queue and its position alone`() {
        playThrough(to = 120, inParty = true)

        val restored = LastPlayed.load()!!
        assertEquals("t0", restored.songs.first().videoId)
        assertEquals("t0", restored.songs[restored.index].videoId)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun `a truncated or corrupt stored queue restores nothing instead of crashing`() {
        playThrough(to = 5)
        val raw = prefs.getString("queue", null)!!
        prefs.edit().putString("queue", raw.take(raw.length / 2)).commit()
        assertNull(LastPlayed.load())
        prefs.edit().putString("queue", "{\"tracks\":[]}").commit()
        assertNull(LastPlayed.load())
    }

    @Test
    fun `older snapshots with index and position inside the json still restore`() {
        prefs.edit().putString(
            "queue",
            "{\"tracks\":[{\"id\":\"a\",\"title\":\"A\",\"artist\":\"X\"},{\"id\":\"b\",\"title\":\"B\",\"artist\":\"X\"}],\"index\":1,\"positionMs\":4200}",
        ).commit()
        val restored = LastPlayed.load()!!
        assertEquals("b", restored.songs[restored.index].videoId)
        assertEquals(4200L, restored.positionMs)
    }

    @Test
    fun `an out of range stored index is clamped to a real track`() {
        playThrough(to = 5)
        prefs.edit().putInt("index", 9_999).putLong("position", -5L).commit()
        val restored = LastPlayed.load()!!
        assertEquals(restored.songs.lastIndex, restored.index)
        assertEquals(0L, restored.positionMs)
    }
}
