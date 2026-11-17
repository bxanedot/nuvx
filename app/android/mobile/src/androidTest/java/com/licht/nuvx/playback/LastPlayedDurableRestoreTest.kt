package com.licht.nuvx.playback

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.licht.nuvx.data.model.QueueTier
import com.licht.nuvx.data.model.Song
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastPlayedDurableRestoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val written = "last_played_durable_written"
    private val reopened = "last_played_durable_reopened"

    @After
    fun tearDown() {
        context.deleteSharedPreferences(written)
        context.deleteSharedPreferences(reopened)
        LastPlayed.init(context)
    }

    @Test
    fun queue_identity_and_order_come_back_from_the_bytes_on_disk() {
        val queue = listOf(
            Song("vid-a", "Alpha", "Artist A", "https://img/a", queueTier = QueueTier.CONTEXT, queueEntryId = "e1"),
            Song("vid-b", "Bravo", "Artist B", null, queueTier = QueueTier.USER_QUEUE, queueEntryId = "e2"),
            Song("vid-c", "Charlie", "Artist C", null, localUri = "content://media/7", localPath = "/music/c.flac", queueEntryId = "e3"),
            Song("vid-d", "Delta", "Artist D", null, queueTier = QueueTier.AUTOPLAY, queueEntryId = "e4"),
            Song("vid-e", "Echo", "Artist E", null, queueEntryId = "e5"),
        )
        LastPlayed.initForTesting(context.getSharedPreferences(written, Context.MODE_PRIVATE))
        LastPlayed.saveQueueImmediately(queue, index = 2, positionMs = 61_500L)

        // A brand-new prefs name has no in-memory cache in this process, so it can only see the copied file.
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(dir, "$written.xml").copyTo(File(dir, "$reopened.xml"), overwrite = true)
        LastPlayed.initForTesting(context.getSharedPreferences(reopened, Context.MODE_PRIVATE))

        val restored = LastPlayed.load()!!
        assertEquals(queue.map { it.videoId }, restored.songs.map { it.videoId })
        assertEquals(queue.map { it.queueEntryId }, restored.songs.map { it.queueEntryId })
        assertEquals(queue.map { it.title }, restored.songs.map { it.title })
        assertEquals(queue.map { it.queueTier }, restored.songs.map { it.queueTier })
        assertEquals("content://media/7", restored.songs[2].localUri)
        assertEquals("/music/c.flac", restored.songs[2].localPath)
        assertEquals(2, restored.index)
        assertEquals(61_500L, restored.positionMs)
    }
}
