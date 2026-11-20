package com.licht.nuvx.data.spotify

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.licht.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Requires Android's real JSONObject/SharedPreferences, not mockable JVM stubs. */
@RunWith(AndroidJUnit4::class)
class LocalPlaylistDurabilityTest {
    @Test
    fun checkpointSurvivesRestartAndRestoresOnlyTheSelectedPlaylistOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nuvx_playlist_checkpoint_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("playlists_json", "[]").commit()
        val first = Song("aaaaaaaaaaa", "A", "Artist", null)
        val second = Song("bbbbbbbbbbb", "B", "Artist", null)
        try {
            LocalPlaylistStore.initForTesting(prefs)
            val playlist = LocalPlaylistStore.createNuvxPlaylist("Original", listOf(first, second))
            val unrelated = LocalPlaylistStore.createNuvxPlaylist("Unrelated", listOf(second))
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(
                playlist.id, listOf(first, second), listOf(second, first),
            ))
            val diskCheckpoint = prefs.getString("reorder_checkpoint_v1", null)
            assertEquals(true, !diskCheckpoint.isNullOrBlank())

            LocalPlaylistStore.initForTesting(prefs) // Re-read durable state.
            assertEquals(listOf(first, second).map { it.videoId },
                LocalPlaylistStore.previousReorderVersion(playlist.id)?.songs?.map { it.videoId })
            assertEquals(true, LocalPlaylistStore.restoreLastReorderVersion(playlist.id))
            assertEquals(false, LocalPlaylistStore.restoreLastReorderVersion(playlist.id), "consumed once")
            LocalPlaylistStore.initForTesting(prefs)
            assertEquals(listOf(first, second).map { it.videoId },
                LocalPlaylistStore.getPlaylist(playlist.id)?.songs?.map { it.videoId })
            assertEquals("Unrelated", LocalPlaylistStore.getPlaylist(unrelated.id)?.title)
            assertEquals(null, prefs.getString("reorder_checkpoint_v1", null))
        } finally {
            context.deleteSharedPreferences(name)
            LocalPlaylistStore.init(context)
        }
    }

    @Test
    fun invalidOrConflictingCheckpointNeverOverwritesNewerPlaylistEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nuvx_playlist_checkpoint_conflict_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("playlists_json", "[]").commit()
        val first = Song("aaaaaaaaaaa", "A", "Artist", null)
        val second = Song("bbbbbbbbbbb", "B", "Artist", null)
        val third = Song("ccccccccccc", "C", "Artist", null)
        try {
            LocalPlaylistStore.initForTesting(prefs)
            val playlist = LocalPlaylistStore.createNuvxPlaylist("Keep", listOf(first, second))
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(
                playlist.id, listOf(first, second), listOf(second, first),
            ))
            assertEquals(true, LocalPlaylistStore.addSong(playlist.id, third))
            assertEquals(null, LocalPlaylistStore.previousReorderVersion(playlist.id),
                "intervening edit conflicts with prior checkpoint")
            assertEquals(false, LocalPlaylistStore.restoreLastReorderVersion(playlist.id))
            prefs.edit().putString("reorder_checkpoint_v1", "{corrupt").commit()
            LocalPlaylistStore.initForTesting(prefs)
            assertEquals(false, LocalPlaylistStore.restoreLastReorderVersion(playlist.id))
            assertEquals(listOf(second, first, third).map { it.videoId },
                LocalPlaylistStore.getPlaylist(playlist.id)?.songs?.map { it.videoId })
        } finally {
            context.deleteSharedPreferences(name)
            LocalPlaylistStore.init(context)
        }
    }

    @Test
    fun failedSynchronousUndoWriteKeepsOriginalStateAfterReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nuvx_playlist_atomic_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("playlists_json", "[]").commit()
        val a = Song("aaaaaaaaaaa", "A", "Artist", null)
        val b = Song("bbbbbbbbbbb", "B", "Artist", null)
        try {
            LocalPlaylistStore.initForTesting(prefs)
            val playlist = LocalPlaylistStore.createNuvxPlaylist("Durable", listOf(a, b))
            val before = prefs.getString("playlists_json", null)
            val rejecting = object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val buffered = prefs.edit()
                    return object : SharedPreferences.Editor by buffered {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            buffered.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean = false // Simulated interrupted write.
                    }
                }
            }

            LocalPlaylistStore.initForTesting(rejecting)
            assertFalse(
                LocalPlaylistStore.replaceSongsIfUnchanged(playlist.id, listOf(a, b), listOf(b, a))
            )
            assertEquals(listOf(a, b), LocalPlaylistStore.getPlaylist(playlist.id)?.songs)
            assertEquals(before, prefs.getString("playlists_json", null))

            LocalPlaylistStore.initForTesting(prefs)
            assertEquals(listOf(a, b), LocalPlaylistStore.getPlaylist(playlist.id)?.songs)
            assertEquals(true, LocalPlaylistStore.replaceSongsIfUnchanged(playlist.id, listOf(a, b), listOf(b, a)))
            LocalPlaylistStore.initForTesting(prefs)
            assertEquals(listOf(b, a), LocalPlaylistStore.getPlaylist(playlist.id)?.songs)
        } finally {
            context.deleteSharedPreferences(name)
            LocalPlaylistStore.init(context)
        }
    }
}
