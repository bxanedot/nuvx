package com.licht.nuvx

import com.licht.nuvx.data.sync.NuvxConnect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvxConnectTest {
    @Test
    fun readsWhatTheComputerSends() {
        val playing = NuvxConnect.parse("""{"track":{"id":"dQw4w9WgXcQ","title":"Never","artist":"Rick","durationMs":213000},"playing":true,"positionMs":61500,"ended":false}""")
        assertEquals(NuvxConnect.Remote("Never", "Rick", true, 61_500, 213_000, id = "dQw4w9WgXcQ"), playing)
        val idle = NuvxConnect.parse("""{"track":null,"playing":false}""")
        assertNull(idle.title)
        assertNull(idle.id)
        assertFalse(idle.playing)
        assertTrue(NuvxConnect.parse("""{"track":{"id":"dQw4w9WgXcQ"},"playing":false,"ended":true}""").ended)
    }

    @Test
    fun loadCarriesTheQueueAndOnlyYouTubeSongs() {
        val tracks = listOfNotNull(
            NuvxConnect.item("dQw4w9WgXcQ", "Never", "Rick"),
            NuvxConnect.item("content://media/7", "Local", "Me"),
            NuvxConnect.item("kJQP7kiw5Fk", "Despacito", "Fonsi"),
        )
        val load = NuvxConnect.load(tracks, 5_000, paused = true)
        assertEquals("load", load["action"]!!.jsonPrimitive.content)
        assertEquals("true", load["paused"]!!.jsonPrimitive.content)
        assertEquals("5000", load["positionMs"]!!.jsonPrimitive.content)
        assertEquals(listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk"), load["tracks"]!!.jsonArray.map { it.jsonObject["videoId"]!!.jsonPrimitive.content })
        assertNull(NuvxConnect.handOff("content://media/7", "Local", "Me", 0))
    }

    @Test
    fun capabilitiesAreSafeForLegacyPartialAndMalformedDeviceResponses() {
        val legacy = NuvxConnect.parse("""{"track":null,"playing":false}""")
        assertTrue("play" in legacy.capabilities)
        assertTrue("load" in legacy.capabilities)
        assertFalse("enqueue" in legacy.capabilities)

        val partial = NuvxConnect.parse("""{"track":null,"playing":false,"capabilities":["play","enqueue","dangerous"]}""")
        assertEquals(setOf("play", "enqueue"), partial.capabilities)
        assertFalse("next" in partial.capabilities)
        assertTrue(NuvxConnect.parse("""{"track":null,"capabilities":[]}""").capabilities.isEmpty())
        assertTrue(NuvxConnect.parse("""{"track":null,"capabilities":"enqueue"}""").capabilities.isEmpty())
        assertNull(NuvxConnect.enqueue("content://local/song", "Local", "Me"))
        val append = NuvxConnect.enqueue("dQw4w9WgXcQ", "Later", "Artist")!!
        assertEquals("enqueue", append["action"]!!.jsonPrimitive.content)
        assertEquals("dQw4w9WgXcQ", append["videoId"]!!.jsonPrimitive.content)
    }

    @Test
    fun queueMutationCannotBeSentToAnOlderOrIncapableHost() {
        val requested = NuvxConnect.enqueue("dQw4w9WgXcQ", "Later", "Artist")!!
        fun host(reply: String, seen: MutableList<String>): OkHttpClient =
            OkHttpClient.Builder().addInterceptor { chain ->
                val method = chain.request().method
                seen += method
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (method == "GET") 200 else 202)
                    .message("OK")
                    .body((if (method == "GET") reply else """{"queued":true}""")
                        .toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val legacyCalls = mutableListOf<String>()
        assertTrue(NuvxConnect.send(requested, host("""{"track":null,"playing":false}""", legacyCalls)).isFailure)
        assertEquals(listOf("GET"), legacyCalls, "no unsupported POST may reach a legacy host")

        val limitedCalls = mutableListOf<String>()
        assertTrue(NuvxConnect.send(requested, host("""{"capabilities":["play"],"track":null}""", limitedCalls)).isFailure)
        assertEquals(listOf("GET"), limitedCalls)

        val capableCalls = mutableListOf<String>()
        assertTrue(NuvxConnect.send(requested, host("""{"capabilities":["play","enqueue"],"track":null}""", capableCalls)).isSuccess)
        assertEquals(listOf("GET", "POST"), capableCalls)
        val legacyPlayCalls = mutableListOf<String>()
        assertTrue(NuvxConnect.send(NuvxConnect.action("play"), host("""{"track":null}""", legacyPlayCalls)).isSuccess)
        assertEquals(listOf("POST"), legacyPlayCalls, "existing legacy controls still work without a capability probe")
    }

    @Test
    fun computerCommandsForThisPhoneAreRead() {
        assertEquals(listOf("toggle", "next"), NuvxConnect.parseCommands("""{"commands":["toggle","next"]}"""))
        assertEquals(emptyList<String>(), NuvxConnect.parseCommands("""{"commands":[]}"""))
        assertEquals(emptyList<String>(), NuvxConnect.parseCommands("""{}"""))
        assertEquals(16, NuvxConnect.parseCommands("{\"commands\":[" + List(40) { "\"next\"" }.joinToString(",") + "]}").size)
    }
}
