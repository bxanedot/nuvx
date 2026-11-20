package com.licht.nuvx.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncClientTest {
    private lateinit var server: MockWebServer
    private lateinit var sync: SyncClient

    @BeforeTest
    fun start() {
        server = MockWebServer().apply { start() }
        sync = SyncClient(server.url("").toString().trimEnd('/'), "tok", OkHttpClient())
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun op(i: Int, size: Int = 10) =
        PendingOp("op$i", "playlist.addTrack", "playlist:1", buildJsonObject { put("videoId", "x".repeat(size)) }, 1L)

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun pushSendsOneBatchInOrderWithTheToken() {
        reply(200, """{"seqs":[1,2]}""")
        val result = sync.push(listOf(op(1), op(2)))
        assertEquals(SyncResult.Pushed(listOf("op1", "op2")), result)
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray
        assertEquals(listOf("op1", "op2"), sent.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun aBatchStaysUnderTheServerLimits() {
        reply(200, "{}")
        val result = assertIs<SyncResult.Pushed>(sync.push((1..500).map { op(it) }))
        assertEquals(SyncClient.MAX_OPS_PER_PUSH, result.ids.size)
        reply(200, "{}")
        val big = assertIs<SyncResult.Pushed>(sync.push((1..20).map { op(it, size = 30_000) }))
        assertTrue(big.ids.size * 30_000 < SyncClient.MAX_BODY_BYTES, "sent ${big.ids.size} ops of 30 KB")
    }

    @Test
    fun anOversizedChangeStopsOnlyOnceItIsFirst() {
        reply(200, "{}")
        assertEquals(SyncResult.Pushed(listOf("op1")), sync.push(listOf(op(1), op(2, size = 40_000))))
        assertIs<SyncResult.Stop>(sync.push(listOf(op(2, size = 40_000))))
    }

    @Test
    fun transientFailuresRetryAndPermanentOnesStop() {
        for (code in listOf(429, 500, 502, 507)) {
            reply(code, """{"error":"x","message":"x"}""")
            assertIs<SyncResult.RetryLater>(sync.push(listOf(op(1))), "HTTP $code")
        }
        for (code in listOf(401, 413, 422)) {
            reply(code, """{"error":"x","message":"x"}""")
            assertIs<SyncResult.Stop>(sync.push(listOf(op(1))), "HTTP $code")
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertIs<SyncResult.RetryLater>(sync.push(listOf(op(1))), "dropped connection")
    }

    @Test
    fun revokedTokenIsDistinguishedFromOrdinaryPermanentFailureForPushAndPull() {
        reply(401, """{"error":"unauthorized"}""")
        val pushed = assertIs<SyncResult.Stop>(sync.push(listOf(op(1))))
        assertTrue(pushed.authorizationLost)
        reply(401, """{"error":"unauthorized"}""")
        val pulled = assertIs<SyncResult.Stop>(sync.pull(0))
        assertTrue(pulled.authorizationLost)
        reply(422, """{"error":"invalid_ops"}""")
        assertTrue(!assertIs<SyncResult.Stop>(sync.push(listOf(op(2)))).authorizationLost)
    }

    @Test
    fun pullReturnsOpsReadyForReplayAndTheNextCursor() {
        reply(200, """{"api":1,"more":true,"ops":[
            {"seq":7,"id":"a","kind":"playlist.create","entity":"playlist:p","payload":{"title":"Mix"}},
            {"seq":9,"id":"b","kind":"playlist.addTrack","entity":"playlist:p","payload":{"videoId":"v1"}},
            {"seq":10,"id":"c","kind":"future.thing","entity":"x:y","payload":null}]}""")
        val page = assertIs<SyncResult.Pulled>(sync.pull(6))
        assertEquals("/v1/ops?after=6", server.takeRequest().path)
        assertEquals(10, page.cursor)
        assertTrue(page.more)
        assertEquals(listOf("v1"), replay(page.ops).playlists.getValue("p").tracks)

        reply(200, """{"api":1,"more":false,"ops":[]}""")
        assertEquals(SyncResult.Pulled(emptyList(), 10, false), sync.pull(10))
    }

    @Test
    fun staleAndStalledPullPagesRetryWithoutAdvancingTheDurableCursor() {
        assertIs<SyncResult.Stop>(sync.pull(-1))
        reply(200, """{"api":1,"more":true,"ops":[]}""")
        assertIs<SyncResult.RetryLater>(sync.pull(4), "no-progress page must not loop forever")
        reply(200, """{"api":1,"more":false,"ops":[
            {"seq":4,"kind":"playlist.create","entity":"playlist:p","payload":{"title":"stale"}}
        ]}""")
        assertIs<SyncResult.RetryLater>(sync.pull(4), "replayed row must not be applied twice")
        reply(200, """{"api":1,"more":true,"ops":[
            {"seq":6,"kind":"playlist.create","entity":"playlist:p","payload":{"title":"First"}},
            {"seq":6,"kind":"playlist.create","entity":"playlist:q","payload":{"title":"duplicate"}}
        ]}""")
        assertIs<SyncResult.RetryLater>(sync.pull(4), "duplicates within a page are rejected")
        reply(503, """{"error":"unavailable"}""")
        assertIs<SyncResult.RetryLater>(sync.pull(4), "temporary failure must retain cursor")
        reply(200, """{"api":1,"more":false,"ops":[
            {"seq":5,"kind":"playlist.create","entity":"playlist:p","payload":{"title":"Recovered"}}
        ]}""")
        val recovered = assertIs<SyncResult.Pulled>(sync.pull(4))
        assertEquals(5, recovered.cursor)
        assertEquals(listOf("Recovered"), replay(recovered.ops).playlists.values.map { it.title })
        assertEquals(30 * 60_000L, retryDelayMs(99), "retry backoff remains capped")
    }

    @Test
    fun aNewerServerStopsTheClient() {
        reply(200, """{"api":2,"more":false,"ops":[]}""")
        assertIs<SyncResult.Stop>(sync.pull(0))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(5_000, retryDelayMs(0))
        assertEquals(10_000, retryDelayMs(1))
        assertEquals(30 * 60_000L, retryDelayMs(50))
    }
}
