package com.licht.nuvx.data.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Kotlin client against the real Nuvx Sync server (`core/sync`, started with Node on a free port and an
 * in-memory database), so the two sides of the protocol cannot drift apart unnoticed. Needs Node 22.18+ on PATH.
 */
class SyncServerContractTest {
    private lateinit var server: SyncTestServer
    private val base get() = server.url
    private val http = OkHttpClient()

    @BeforeTest
    fun start() { server = SyncTestServer() }

    @AfterTest
    fun stop() = server.close()

    private fun client(token: String? = null) = SyncClient(base, token, http)
    private fun op(id: String, kind: String, entity: String, vararg kv: Pair<String, String?>) =
        PendingOp(id, kind, entity, buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }, 1L)

    private fun pullAll(sync: SyncClient): List<SyncOp> {
        val ops = ArrayList<SyncOp>()
        var cursor = 0L
        while (true) {
            val page = assertIs<SyncResult.Pulled>(sync.pull(cursor))
            ops += page.ops
            cursor = page.cursor
            if (!page.more) return ops
        }
    }

    @Test
    fun twoDevicesPairSyncAndRevoke() {
        val phone = assertIs<SyncResult.Joined>(client().createSpace("Phone"))
        val phoneSync = client(phone.token)

        // QR pairing: the TV shows a code, the phone approves it, the TV claims its own token once.
        val pairing = assertIs<SyncResult.PairingStarted>(client().startPairing("Living room TV"))
        assertEquals(SyncResult.Waiting, client().claim(pairing.code, pairing.secret))
        assertIs<SyncResult.Stop>(client().claim(pairing.code, "wrong-secret"), "the code alone grants nothing")
        assertEquals(SyncResult.Approved("Living room TV"), phoneSync.approve(pairing.code))
        val tv = assertIs<SyncResult.Joined>(client().claim(pairing.code, pairing.secret))
        assertEquals(phone.spaceId, tv.spaceId)
        assertIs<SyncResult.Stop>(client().claim(pairing.code, pairing.secret), "a pairing works once")
        val tvSync = client(tv.token)

        // Offline edits on both devices, pushed in any order, replay to the same state everywhere.
        val phoneOps = listOf(
            op("p1", "playlist.create", "playlist:road", "title" to "Road"),
            op("p2", "playlist.addTrack", "playlist:road", "videoId" to "aaaaaaaaaaa"),
        )
        assertEquals(SyncResult.Pushed(listOf("p1", "p2")), phoneSync.push(phoneOps))
        assertEquals(SyncResult.Pushed(listOf("t1")), tvSync.push(listOf(op("t1", "playlist.addTrack", "playlist:road", "videoId" to "bbbbbbbbbbb", "after" to null))))
        assertEquals(SyncResult.Pushed(listOf("p1", "p2")), phoneSync.push(phoneOps), "a retried push is harmless")

        val onPhone = replay(pullAll(phoneSync))
        assertEquals(onPhone, replay(pullAll(tvSync)))
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), onPhone.playlists.getValue("road").tracks)
        assertEquals(3, pullAll(phoneSync).size, "the retry stored nothing twice")

        // Revocation: the phone removes the TV; the TV's next call stops instead of retrying forever.
        val devices = assertIs<SyncResult.Devices>(phoneSync.devices())
        assertEquals(setOf("Phone", "Living room TV"), devices.devices.map { it.name }.toSet())
        assertEquals(SyncResult.Done, phoneSync.revoke(tv.deviceId))
        assertIs<SyncResult.Stop>(tvSync.pull(0))
        assertIs<SyncResult.Stop>(tvSync.push(listOf(op("t2", "favorite.set", "track:x", "on" to "true"))))

        // Deleting the space removes server data and access; the phone stops too.
        assertEquals(SyncResult.Done, phoneSync.deleteSpace())
        assertTrue(phoneSync.pull(0) is SyncResult.Stop)
    }
}
