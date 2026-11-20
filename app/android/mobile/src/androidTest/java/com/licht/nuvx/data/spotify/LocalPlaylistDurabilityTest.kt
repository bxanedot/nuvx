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
