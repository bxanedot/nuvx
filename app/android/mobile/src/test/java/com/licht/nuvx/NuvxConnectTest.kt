package com.licht.nuvx

import com.licht.nuvx.data.sync.NuvxConnect
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NuvxConnectTest {
    @Test
    fun readsWhatTheComputerSends() {
        val playing = NuvxConnect.parse("""{"track":{"id":"dQw4w9WgXcQ","title":"Never","artist":"Rick","durationMs":213000},"playing":true,"positionMs":61500}""")
        assertEquals(NuvxConnect.Remote("Never", "Rick", true, 61_500, 213_000), playing)
        val idle = NuvxConnect.parse("""{"track":null,"playing":false}""")
        assertNull(idle.title)
        assertFalse(idle.playing)
    }

    @Test
    fun onlyYouTubeSongsCanBeHandedOver() {
        val handOff = NuvxConnect.handOff("dQw4w9WgXcQ", "Never", "Rick", 5_000)!!
        assertEquals("playTrack", handOff["action"]!!.jsonPrimitive.content)
        assertEquals("5000", handOff["positionMs"]!!.jsonPrimitive.content)
        assertNull(NuvxConnect.handOff("content://media/7", "Local", "Me", 0))
    }
}
