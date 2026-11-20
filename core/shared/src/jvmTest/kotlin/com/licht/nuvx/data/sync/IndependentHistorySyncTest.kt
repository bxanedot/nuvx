package com.licht.nuvx.data.sync

import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndependentHistorySyncTest {
    private lateinit var server: SyncTestServer
    private val http = OkHttpClient()
    private class Store : SyncStore {
        var saved: String? = null
        override fun load(): String? = saved
        override fun save(state: String) { saved = state }
    }

    @BeforeTest fun start() { server = SyncTestServer() }
    @AfterTest fun stop() = server.close()

    @Test
    fun optOutSendsNothingAndOptedInPlaySurvivesCrashAndDuplicateDelivery() {
        val owner = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).createSpace("Phone"))
        var enabled = false
        val store = Store()
        val history = IndependentSyncChannel(
            SyncClient(server.url, owner.token, http), store, { enabled },
            category = IndependentCategory.HISTORY
        )
        assertFalse(history.recordPlay("dQw4w9WgXcQ", 1_000))
        assertIs<IndependentResult.Disabled>(history.sync())
        assertEquals(null, store.saved, "no event persisted or transmitted when not opted in")

        enabled = true
        val offline = IndependentSyncChannel(
            SyncClient("http://127.0.0.1:9", owner.token, http), store, { enabled },
            newId = { "history-unique-1" }, category = IndependentCategory.HISTORY
        )
        assertFalse(offline.recordPlay("file:///private/music.mp3", 1_000), "never share a local file URI")
        assertTrue(offline.recordPlay("dQw4w9WgXcQ", 1_000))
        assertIs<IndependentResult.Failed>(offline.sync())
        assertEquals(1, offline.current().pending.size)

        val restarted = IndependentSyncChannel(
            SyncClient(server.url, owner.token, http), store, { enabled },
            category = IndependentCategory.HISTORY
        )
        assertEquals(1, restarted.current().history.size)
        assertIs<IndependentResult.Applied>(restarted.sync())
        assertTrue(restarted.current().pending.isEmpty())
        assertIs<IndependentResult.Applied>(restarted.sync())
        assertEquals(1, restarted.current().history.size, "replaying a fetched event never doubles its count")

        val paired = assertIs<SyncResult.PairingStarted>(SyncClient(server.url, null, http).startPairing("Tablet"))
        assertIs<SyncResult.Approved>(SyncClient(server.url, owner.token, http).approve(paired.code))
        val tablet = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).claim(paired.code, paired.secret))
        val read = IndependentSyncChannel(
            SyncClient(server.url, tablet.token, http), Store(), { true },
            category = IndependentCategory.HISTORY
        )
        val events = assertIs<IndependentResult.Applied>(read.sync()).state.history
        assertEquals(listOf(SyncedListen("history-unique-1", "dQw4w9WgXcQ", 1_000)), events)
        enabled = false
        assertIs<IndependentResult.Disabled>(restarted.sync())
    }
}
