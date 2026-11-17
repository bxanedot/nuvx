package com.licht.nuvx.data.sync

import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuvxSyncTest {
    @Test
    fun syncServersMustUseHttpsExceptOnThisMachine() {
        assertEquals("https://sync.example.org", NuvxSync.normalizeServer(" sync.example.org/ "))
        assertEquals("https://sync.example.org:8443/nuvx", NuvxSync.normalizeServer("https://sync.example.org:8443/nuvx"))
        assertEquals("http://10.0.2.2:8787", NuvxSync.normalizeServer("http://10.0.2.2:8787"))
        assertNull("a device token must not travel in clear text", NuvxSync.normalizeServer("http://sync.example.org"))
        assertNull(NuvxSync.normalizeServer("https://user:pass@sync.example.org"))
        assertNull(NuvxSync.normalizeServer("https://sync.example.org/?redirect=evil"))
        assertNull(NuvxSync.normalizeServer("ftp://sync.example.org"))
        assertNull(NuvxSync.normalizeServer(""))
    }

    @Test
    fun applyingASyncResultKeepsTheRicherLocalSong() {
        val local = Song(videoId = "a", title = "Song", artist = "Artist", thumbnailUrl = null, durationText = "3:21", albumName = "LP")
        val synced = listOf(
            LibraryPlaylist("p", "Road", listOf(Song("a", "Song", "Artist", null), Song("b", "Other", "B", "https://i.ytimg.com/b.jpg"))),
        )
        val result = NuvxSync.toLocal(synced, listOf(LocalPlaylist("q", "Old", listOf(local))))
        assertEquals(listOf(LocalPlaylist("p", "Road", listOf(local, synced[0].songs[1]))), result)
    }
}
