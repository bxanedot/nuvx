package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioSettingsTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun repeatAvoidanceDefaultsOnAndPersists() {
        val store = MemoryStore()

        assertTrue(RadioSettings.load(store).avoidRepeats)
        RadioSettings(avoidRepeats = false).save(store)
        assertFalse(RadioSettings.load(store).avoidRepeats)
        RadioSettings(avoidRepeats = true).save(store)
        assertTrue(RadioSettings.load(store).avoidRepeats)
    }
}
