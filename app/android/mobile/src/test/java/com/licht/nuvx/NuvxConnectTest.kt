package com.licht.nuvx

import com.licht.nuvx.data.sync.NuvxConnect
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
}
