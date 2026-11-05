package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedTest {
    private class Store : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun missingOrUnsupportedSavedSpeedUsesNormalPlayback() {
        val store = Store()

        assertEquals(1f, PlaybackSpeed.read(store))
        store.put("playbackSpeed", "8")
        assertEquals(1f, PlaybackSpeed.read(store))
        store.put("playbackSpeed", "fast")
        assertEquals(1f, PlaybackSpeed.read(store))
    }

    @Test
    fun supportedSpeedRoundTripsThroughThePreferenceStore() {
        val store = Store()

        PlaybackSpeed.write(store, 1.5f)

        assertEquals(1.5f, PlaybackSpeed.read(store))
        assertEquals(1f, PlaybackSpeed.write(store, 4f))
        assertEquals(1f, PlaybackSpeed.read(store))
        assertEquals(0.75f, PlaybackSpeed.normalize(0.75f))
        assertEquals(1f, PlaybackSpeed.normalize(Float.NaN))
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
    }
}
