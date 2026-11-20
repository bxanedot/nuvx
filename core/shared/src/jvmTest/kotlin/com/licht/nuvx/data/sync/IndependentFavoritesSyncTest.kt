package com.licht.nuvx.data.sync

import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndependentFavoritesSyncTest {
    private lateinit var server: SyncTestServer
    private val http = OkHttpClient()
    private class MemoryStore : SyncStore {
        var data: String? = null
        override fun load(): String? = data
        override fun save(state: String) { data = state }
    }

    @BeforeTest fun start() { server = SyncTestServer() }
    @AfterTest fun close() = server.close()

    @Test
    fun defaultOffOfflineFavoriteAndUnfavoriteResumeAfterRestartAndConverge() {
        val owner = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).createSpace("Phone"))
        val token = owner.token
        val store = MemoryStore()
        var optedIn = false
        val old = IndependentSyncChannel(SyncClient(server.url, token, http), store, { optedIn })
        assertFalse(old.setFavorite("dQw4w9WgXcQ", true), "no favorite is saved for upload without consent")
        assertIs<IndependentResult.Disabled>(old.sync())
        assertTrue(store.data == null, "opt-out performs no disk or network work")

        optedIn = true
        val offline = IndependentSyncChannel(SyncClient("http://127.0.0.1:9", token, http), store, { optedIn })
        assertTrue(offline.setFavorite("dQw4w9WgXcQ", true))
        assertIs<IndependentResult.Failed>(offline.sync())
        assertEquals(1, offline.current().pending.size)
        val reopened = IndependentSyncChannel(SyncClient(server.url, token, http), store, { optedIn })
        assertEquals(true, reopened.current().favorites["dQw4w9WgXcQ"])
        assertIs<IndependentResult.Applied>(reopened.sync())
        assertTrue(reopened.current().pending.isEmpty())

        val paired = assertIs<SyncResult.PairingStarted>(SyncClient(server.url, null, http).startPairing("Tablet"))
        assertIs<SyncResult.Approved>(SyncClient(server.url, token, http).approve(paired.code))
        val tablet = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).claim(paired.code, paired.secret))
        val other = IndependentSyncChannel(SyncClient(server.url, tablet.token, http), MemoryStore(), { true })
        assertEquals(true, assertIs<IndependentResult.Applied>(other.sync()).state.favorites["dQw4w9WgXcQ"])

        assertTrue(offline.setFavorite("dQw4w9WgXcQ", false))
        assertIs<IndependentResult.Applied>(reopened.sync())
        assertEquals(false, assertIs<IndependentResult.Applied>(other.sync()).state.favorites["dQw4w9WgXcQ"])
        optedIn = false
        assertIs<IndependentResult.Disabled>(reopened.sync())
        assertEquals(false, reopened.current().favorites["dQw4w9WgXcQ"])
    }
}
