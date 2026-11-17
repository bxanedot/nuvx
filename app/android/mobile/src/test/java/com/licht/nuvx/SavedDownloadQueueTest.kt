package com.licht.nuvx

import com.licht.nuvx.data.model.Song
import com.licht.nuvx.download.SavedDownloadQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDownloadQueueTest {

    @Test
    fun aSavedQueueComesBackInOrderWithWhatADownloadNeeds() {
        val songs = listOf(
            Song("a1", "First", "Artist", "https://i.ytimg.com/a.jpg", albumId = "MPRE1", albumName = "Album"),
            Song("b2", "Second", "Other", null, isVideo = true),
        )
        val folder = kotlin.io.path.createTempDirectory("nuvx-queue").toFile()
        val file = java.io.File(folder, "download_queue.json")
        SavedDownloadQueue.write(file, songs)
        val back = SavedDownloadQueue.read(file)
        assertEquals(songs.map { it.videoId }, back.map { it.videoId })
        assertEquals("Album", back[0].albumName)
        assertEquals("https://i.ytimg.com/a.jpg", back[0].thumbnailUrl)
        assertTrue(back[1].isVideo)
        folder.deleteRecursively()
    }

    @Test
    fun aDamagedFileResumesNothingInsteadOfCrashing() {
        assertTrue(SavedDownloadQueue.decode("{not json").isEmpty())
        assertEquals(listOf("ok"), SavedDownloadQueue.decode("""[{"title":"no id"},{"videoId":"ok"}]""").map { it.videoId })
    }
}
