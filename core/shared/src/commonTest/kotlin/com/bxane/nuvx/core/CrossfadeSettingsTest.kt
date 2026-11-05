package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CrossfadeSettingsTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    @Test
    fun defaultsOffAndPersistsOnlySupportedDurations() {
        val store = MemoryStore()
        assertEquals(0, CrossfadeSettings.read(store))
        CrossfadeSettings.write(store, 5)
        assertEquals(5, CrossfadeSettings.read(store))
        CrossfadeSettings.write(store, 7)
        assertEquals(0, CrossfadeSettings.read(store))
    }
}
