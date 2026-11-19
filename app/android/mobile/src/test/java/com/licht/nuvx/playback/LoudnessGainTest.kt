package com.licht.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessGainTest {
    @Test
    fun defaultTargetCancelsYouTubeLoudness() {
        assertEquals(-300, loudnessGainMb(loudnessDb = 3.0, targetLufs = -14))
        assertEquals(200, loudnessGainMb(loudnessDb = -2.0, targetLufs = -14))
    }

    @Test
    fun quieterTargetLowersGain() {
        assertEquals(-500, loudnessGainMb(loudnessDb = 3.0, targetLufs = -16))
    }

    @Test
    fun gainStaysWithinSafeRange() {
        assertEquals(300, loudnessGainMb(loudnessDb = -12.0, targetLufs = -10))
        assertEquals(-1500, loudnessGainMb(loudnessDb = 20.0, targetLufs = -20))
    }

    @Test
    fun `agrees with the shared loudness gain cases`() {
        // The same cases the shared Rust core (core/rust/nuvx-core) is tested against.
        val file = java.io.File("../../../core/contracts/loudness-gain.json").takeIf { it.isFile }
            ?: java.io.File("core/contracts/loudness-gain.json")
        val cases = (kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject)["cases"]
            as kotlinx.serialization.json.JsonArray
        for (case in cases) {
            val c = case as kotlinx.serialization.json.JsonObject
            fun v(k: String) = (c[k] as kotlinx.serialization.json.JsonPrimitive).content
            val got = loudnessGainMb(v("loudnessDb").toDouble(), v("targetLufs").toInt())
            org.junit.Assert.assertEquals(v("name"), Math.round(v("gainDb").toDouble() * 100).toInt(), got)
        }
    }
}
