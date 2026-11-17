package com.licht.nuvx

import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylist
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import com.licht.nuvx.data.stats.Backup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlaylistsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun playlistsSurviveABackupInOrder() {
        val mix = LocalPlaylist("nuvx_local_1", "Mix", listOf(
            Song("a", "One", "Artist", "https://i.ytimg.com/a.jpg", albumName = "Album"),
            Song("b", "Two", "Artist", null, isVideo = true),
        ))
        val file = Backup.BackupFile(playlists = listOf(Backup.BackupPlaylist.of(mix)))
        val back = json.decodeFromString(Backup.BackupFile.serializer(), json.encodeToString(Backup.BackupFile.serializer(), file))
        val restored = back.playlists.single().toLocal()
        assertEquals("Mix", restored.title)
        assertEquals(listOf("a", "b"), restored.songs.map { it.videoId })
        assertEquals("Album", restored.songs[0].albumName)
        assertTrue(restored.songs[1].isVideo)
    }

    @Test
    fun aBackupFromBeforePlaylistsStillReads() {
        val old = json.decodeFromString(Backup.BackupFile.serializer(), """{"app":"nuvx","version":1,"settings":{},"listening":[]}""")
        assertTrue(old.playlists.isEmpty())
    }

    @Test
    fun restoringNeverOverwritesAPlaylistThatExists() {
        val id = "nuvx_local_merge_test"
        LocalPlaylistStore.deletePlaylist(id)
        assertEquals(1, LocalPlaylistStore.mergeIn(listOf(LocalPlaylist(id, "Mine", emptyList()))))
        assertEquals(0, LocalPlaylistStore.mergeIn(listOf(LocalPlaylist(id, "From backup", listOf(Song("x", "X", "Y", null))))))
        assertEquals("Mine", LocalPlaylistStore.getPlaylist(id)?.title)
        LocalPlaylistStore.deletePlaylist(id)
    }

    @Test
    fun undoingADeletePutsThePlaylistBackInPlaceWithItsSongs() {
        val ids = listOf("undo_a", "undo_b", "undo_c")
        ids.forEach(LocalPlaylistStore::deletePlaylist)
        ids.reversed().forEach { LocalPlaylistStore.restore(LocalPlaylist(it, it, listOf(Song("v_$it", "T", "A", null))), 0) }
        val before = LocalPlaylistStore.playlists.value.map { it.id }.filter { it in ids }
        val removed = LocalPlaylistStore.getPlaylist("undo_b")!!
        val at = LocalPlaylistStore.playlists.value.indexOf(removed)
        LocalPlaylistStore.deletePlaylist("undo_b")
        LocalPlaylistStore.restore(removed, at)
        assertEquals(before, LocalPlaylistStore.playlists.value.map { it.id }.filter { it in ids })
        assertEquals(listOf("v_undo_b"), LocalPlaylistStore.getPlaylist("undo_b")!!.songs.map { it.videoId })
        ids.forEach(LocalPlaylistStore::deletePlaylist)
    }
}
