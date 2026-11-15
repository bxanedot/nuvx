package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.data.OfflinePlaylist
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaylistShelfTest {
    @Test
    fun userCreatedOfflinePlaylistsAppearInLibraryWithoutLikedSongs() {
        val playlists = listOf(
            OfflinePlaylist(OfflinePlaylists.LIKED_ID, "Liked songs", emptyList()),
            OfflinePlaylist("p1", "Device mix", listOf(Song("track", "Track", "Artist", thumbnailUrl = "https://img.test/track.jpg"))),
            OfflinePlaylist("p2", "Artwork fallback", listOf(Song("track2", "Track", "Artist", thumbnailUrl = "https://img.test/fallback.jpg"))),
            OfflinePlaylist("p3", "Empty mix", emptyList()),
        )

        val items = offlinePlaylistShelfItems(
            playlists,
            subtitleFor = { it.toString() },
            artworkFor = { if (it == "local:offline:p1") "content://cover" else null },
        )

        assertEquals(listOf("local:offline:p1", "local:offline:p2", "local:offline:p3"), items.map { it.browseId })
        assertEquals("content://cover", items[0].thumbnailUrl)
        assertEquals("1", items[0].subtitle)
        assertEquals("https://img.test/fallback.jpg", items[1].thumbnailUrl)
        assertNull(items[2].thumbnailUrl)
    }
}
