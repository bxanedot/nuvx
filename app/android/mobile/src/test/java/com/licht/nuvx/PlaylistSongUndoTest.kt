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

    @Test
    fun redoReappliesExactOrderAndRefusesToOverwriteInterveningEdit() {
        val id = LocalPlaylistStore.createNuvxPlaylist("Redo", listOf(a, b, c)).id
        try {
            val original = listOf(a, b, c)
            val reordered = listOf(c, a, b)
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(id, original, reordered))
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(id, reordered, original))
            assertEquals(original, LocalPlaylistStore.getPlaylist(id)?.songs)
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(id, original, reordered))
            assertEquals(reordered, LocalPlaylistStore.getPlaylist(id)?.songs)

            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(id, reordered, original))
            LocalPlaylistStore.addSong(id, Song("ddddddddddd", "D", "x", null))
            assertFalse(LocalPlaylistStore.replaceSongsIfUnchanged(id, original, reordered))
            assertEquals(listOf(a, b, c, Song("ddddddddddd", "D", "x", null)), LocalPlaylistStore.getPlaylist(id)?.songs)
        } finally {
            LocalPlaylistStore.deletePlaylist(id)
        }
    }

    @Test
    fun failedDurableUndoWriteLeavesCurrentPlaylistAndPreviousSnapshotIntact() {
        val prefs = FakeSharedPreferences()
        LocalPlaylistStore.initForTesting(prefs)
        val playlist = LocalPlaylistStore.createNuvxPlaylist("Durable", listOf(a, b, c))
        val before = prefs.getString("playlists_json", null)
        val rejecting = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : android.content.SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = false
                }
            }
        }
        try {
            LocalPlaylistStore.initForTesting(rejecting)
            assertFalse(LocalPlaylistStore.replaceSongsIfUnchanged(playlist.id, listOf(a, b, c), listOf(c, a, b)))
            assertEquals(listOf(a, b, c), LocalPlaylistStore.getPlaylist(playlist.id)?.songs)
            assertEquals(before, prefs.getString("playlists_json", null))
            LocalPlaylistStore.initForTesting(prefs)
            assertEquals(listOf(a, b, c), LocalPlaylistStore.getPlaylist(playlist.id)?.songs)
        } finally {
            LocalPlaylistStore.initForTesting(prefs)
            LocalPlaylistStore.deletePlaylist(playlist.id)
        }
    }

    @Test
    fun renameThroughTheMenuIdAndUndoRestoreTheName() {
        val playlist = LocalPlaylistStore.createNuvxPlaylist("Old name", listOf(a))
        try {
            // The menu passes the browse id ("local:playlist:<id>"), not the bare id.
            LocalPlaylistStore.renamePlaylist(playlist.browseId, "New name")
            assertEquals("New name", LocalPlaylistStore.getPlaylist(playlist.id)?.title)
            LocalPlaylistStore.renamePlaylist(playlist.browseId, "Old name")
            assertEquals("Old name", LocalPlaylistStore.getPlaylist(playlist.id)?.title)
        } finally {
            LocalPlaylistStore.deletePlaylist(playlist.id)
        }
    }

    @Test
    fun reorderingThenUndoingRestoresTheOldOrder() {
        val id = LocalPlaylistStore.createNuvxPlaylist("Order", listOf(a, b, c)).id
        try {
            // What the reorder screen saves, and what Undo saves back (the old list).
            assertEquals(true, LocalPlaylistStore.replaceSongs(id, listOf(c, a, b)))
            assertEquals(listOf(c, a, b), LocalPlaylistStore.getPlaylist(id)?.songs)
            assertEquals(true, LocalPlaylistStore.replaceSongs(id, listOf(a, b, c)))
            assertEquals(listOf(a, b, c), LocalPlaylistStore.getPlaylist(id)?.songs)
        } finally {
            LocalPlaylistStore.deletePlaylist(id)
        }
    }
}
