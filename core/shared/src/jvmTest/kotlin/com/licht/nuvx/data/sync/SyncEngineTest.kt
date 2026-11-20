package com.licht.nuvx.data.sync

import com.licht.nuvx.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncEngineTest {
    private class MemoryStore : SyncStore {
        var state: String? = null
        override fun load() = state
        override fun save(state: String) { this.state = state }
    }

    private fun song(id: String) = Song(videoId = id, title = "T$id", artist = "A$id", thumbnailUrl = "https://i.ytimg.com/$id.jpg")
    private fun playlist(id: String, title: String, vararg tracks: String) = LibraryPlaylist(id, title, tracks.map(::song))
    private fun ids(p: List<LibraryPlaylist>) = p.associate { it.id to (it.title to it.songs.map(Song::videoId)) }

    @Test
    fun diffReplaysToTheTargetOnRandomEdits() {
        val engine = SyncEngine(SyncClient("http://unused", null), MemoryStore())
        val rnd = Random(7)
        repeat(300) {
            val before = (0 until rnd.nextInt(0, 8)).map { "v$it" }.shuffled(rnd)
            val after = ((0 until rnd.nextInt(0, 8)).map { "v${it + rnd.nextInt(0, 4)}" }).distinct().shuffled(rnd)
            val ops = engine.diff(listOf(SnapshotPlaylist("p", "Old", before)), listOf(playlist("p", "New", *after.toTypedArray())))
            // Seed the log with the "before" state, then replay the diff on top of it.
            val seed = listOf(SyncOp(1, "playlist.create", "playlist:p", Json.parseToJsonElement("""{"title":"Old"}""") as JsonObject)) +
                before.mapIndexed { i, v -> SyncOp(2L + i, "playlist.addTrack", "playlist:p", Json.parseToJsonElement("""{"videoId":"$v"}""") as JsonObject) }
            val diffOps = ops.mapIndexed { i, op -> SyncOp(100L + i, op.kind, op.entity, Json.parseToJsonElement(op.payload) as JsonObject) }
            val result = replay(seed + diffOps).playlists.getValue("p")
            assertEquals(after, result.tracks, "before=$before after=$after")
            assertEquals("New", result.title)
        }
    }

    private lateinit var server: SyncTestServer

    @BeforeTest
    fun start() { server = SyncTestServer() }

    @AfterTest
    fun stop() = server.close()

    private fun device(token: String) = SyncEngine(SyncClient(server.url, token, OkHttpClient()), MemoryStore())

    @Test
    fun twoDevicesConvergeAndOfflineEditsSurvive() {
        val http = OkHttpClient()
        val phoneJoin = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).createSpace("Phone"))
        val pairing = assertIs<SyncResult.PairingStarted>(SyncClient(server.url, null, http).startPairing("Tablet"))
        SyncClient(server.url, phoneJoin.token, http).approve(pairing.code)
        val tabletJoin = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).claim(pairing.code, pairing.secret))
        val phone = device(phoneJoin.token)
        val tablet = device(tabletJoin.token)

        // Each device starts with its own playlists; joining merges them, nothing is lost.
        var phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(listOf(playlist("road", "Road", "a", "b", "c")))).playlists
        var tabletLib = assertIs<SyncOutcome.Apply>(tablet.syncOnce(listOf(playlist("gym", "Gym", "x")))).playlists
        phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(phoneLib)).playlists
        assertEquals(ids(phoneLib), ids(tabletLib))
        assertEquals(setOf("road", "gym"), phoneLib.map { it.id }.toSet())
        assertEquals("Ta", phoneLib.first { it.id == "road" }.songs.first().title.take(2), "metadata travels with the track")

        // Offline on both: the phone moves c to the front, the tablet adds d after b. Both edits survive.
        phoneLib = phoneLib.map { if (it.id == "road") playlist("road", "Road", "c", "a", "b") else it }
        tabletLib = tabletLib.map { if (it.id == "road") playlist("road", "Road", "a", "b", "d", "c") else it }
        phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(phoneLib)).playlists
        tabletLib = assertIs<SyncOutcome.Apply>(tablet.syncOnce(tabletLib)).playlists
        phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(phoneLib)).playlists
        assertEquals(ids(phoneLib), ids(tabletLib))
        assertEquals(listOf("c", "a", "b", "d"), phoneLib.first { it.id == "road" }.songs.map { it.videoId })

        // A delete syncs; undoing it later brings the playlist back under a new id instead of being ignored.
        val gym = phoneLib.first { it.id == "gym" }
        phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(phoneLib - gym)).playlists
        tabletLib = assertIs<SyncOutcome.Apply>(tablet.syncOnce(tabletLib)).playlists
        assertTrue(tabletLib.none { it.title == "Gym" })
        phoneLib = assertIs<SyncOutcome.Apply>(phone.syncOnce(phoneLib + gym)).playlists
        val restored = phoneLib.single { it.title == "Gym" }
        assertNotEquals("gym", restored.id)
        assertEquals(listOf("x"), restored.songs.map { it.videoId })
    }

    @Test
    fun revokedDeviceStopsWithoutDiscardingItsOfflineOutboxOrChangingLocalPlaylists() {
        val http = OkHttpClient()
        val owner = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).createSpace("Owner"))
        val pairing = assertIs<SyncResult.PairingStarted>(SyncClient(server.url, null, http).startPairing("Revoked"))
        assertIs<SyncResult.Approved>(SyncClient(server.url, owner.token, http).approve(pairing.code))
        val joined = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).claim(pairing.code, pairing.secret))
        val store = MemoryStore()
        val device = SyncEngine(SyncClient(server.url, joined.token, http), store)
        val initial = listOf(playlist("road", "Road", "a"))
        assertIs<SyncOutcome.Apply>(device.syncOnce(initial))
        assertEquals(SyncResult.Done, SyncClient(server.url, owner.token, http).revoke(joined.deviceId))

        val edited = listOf(playlist("road", "Road", "a", "b"))
        val stopped = assertIs<SyncOutcome.Stop>(device.syncOnce(edited))
        assertTrue(stopped.authorizationLost)
        val pending = Json.decodeFromString(EngineState.serializer(), store.state!!)
        assertTrue(pending.outbox.isNotEmpty(), "revocation must preserve unsent local edits")
        assertEquals(listOf("a", "b"), edited.single().songs.map(Song::videoId))
        assertIs<SyncOutcome.Stop>(device.syncOnce(edited), "the server never accepts stale credentials")
    }

    @Test
    fun offlineChangesWaitInTheOutboxAndNothingIsApplied() {
        val join = assertIs<SyncResult.Joined>(SyncClient(server.url, null, OkHttpClient()).createSpace("Phone"))
        val store = MemoryStore()
        val offline = SyncEngine(SyncClient("http://127.0.0.1:9", join.token, OkHttpClient()), store)
        assertIs<SyncOutcome.RetryLater>(offline.syncOnce(listOf(playlist("road", "Road", "a"))))
        // Back online with the same saved state: the queued change is sent, then applied everywhere.
        val online = SyncEngine(SyncClient(server.url, join.token, OkHttpClient()), store)
        val lib = assertIs<SyncOutcome.Apply>(online.syncOnce(listOf(playlist("road", "Road", "a")))).playlists
        assertEquals(listOf("a"), lib.single().songs.map { it.videoId })
        val fresh = assertIs<SyncOutcome.Apply>(device(join.token).syncOnce(emptyList())).playlists
        assertEquals(ids(lib), ids(fresh), "a new install of the same device sees what was sent")
    }
}
