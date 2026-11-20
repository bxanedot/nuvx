package com.licht.nuvx.playback

import com.licht.nuvx.FakeSharedPreferences
import android.content.SharedPreferences
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
    fun `service teardown checkpoint survives reread without duplicating queue entries`() {
        val trackList = queue.take(5)
        LastPlayed.saveQueueImmediately(trackList, index = 3, positionMs = 15_250)
        // Teardown may be invoked after an ordinary async checkpoint. The
        // synchronous write must leave one ordered copy, with the last position.
        LastPlayed.saveQueue(trackList, index = 3)
        LastPlayed.saveQueueImmediately(trackList, index = 3, positionMs = 15_750)
        LastPlayed.initForTesting(prefs) // Fresh instance loading persisted preferences
        val recovered = LastPlayed.load()!!
        assertEquals(trackList.map { it.videoId }, recovered.songs.map { it.videoId })
        assertEquals(3, recovered.index)
        assertEquals(15_750L, recovered.positionMs)
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
    fun `an interrupted synchronous queue write keeps the previous durable snapshot`() {
        LastPlayed.saveQueueImmediately(queue.take(3), 1, 12_000L)
        val before = LastPlayed.load()!!
        // Abort an editor before commit: no queue, index, or position key may
        // become visible, rather than partially updating the three keys.
        val aborted = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor =
                object : SharedPreferences.Editor by prefs.edit() {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                    override fun apply() = Unit
                }
        }
        LastPlayed.initForTesting(aborted)
        LastPlayed.saveQueueImmediately(queue.drop(10).take(3), 0, 99_000L)
        LastPlayed.initForTesting(prefs)
        val after = LastPlayed.load()!!
        assertEquals(before.songs.map(Song::videoId), after.songs.map(Song::videoId))
        assertEquals(before.index, after.index)
        assertEquals(before.positionMs, after.positionMs)
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
