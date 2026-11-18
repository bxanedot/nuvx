package com.licht.nuvx

import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaylistSongUndoTest {
    private val a = Song("aaaaaaaaaaa", "A", "x", null)
    private val b = Song("bbbbbbbbbbb", "B", "x", null)
    private val c = Song("ccccccccccc", "C", "x", null)

    @Test
    fun aRemovedSongComesBackAtItsOldPlace() {
        val id = LocalPlaylistStore.createNuvxPlaylist("Undo", listOf(a, b, c)).id
        try {
            LocalPlaylistStore.removeSong(id, b.videoId)
            LocalPlaylistStore.putBack(id, b, 1)
            assertEquals(listOf(a, b, c), LocalPlaylistStore.getPlaylist(id)?.songs)
        } finally {
            LocalPlaylistStore.deletePlaylist(id)
        }
    }

    @Test
    fun undoNeverDuplicatesASongAddedBackMeanwhile() {
        val id = LocalPlaylistStore.createNuvxPlaylist("Undo twice", listOf(a, b)).id
        try {
            LocalPlaylistStore.removeSong(id, a.videoId)
            LocalPlaylistStore.addSong(id, a)
            assertFalse(LocalPlaylistStore.putBack(id, a, 0))
            assertEquals(listOf(b, a), LocalPlaylistStore.getPlaylist(id)?.songs)
        } finally {
            LocalPlaylistStore.deletePlaylist(id)
        }
    }
}
