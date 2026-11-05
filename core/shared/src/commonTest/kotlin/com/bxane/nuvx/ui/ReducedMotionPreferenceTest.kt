package com.bxane.nuvx.ui

import com.bxane.nuvx.core.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReducedMotionPreferenceTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun wearOsDefaultsToReducedMotionAndOtherTargetsDoNot() {
        val store = MemoryStore()
        assertTrue(ReducedMotionPreference.read(store, wearOs = true))
        assertFalse(ReducedMotionPreference.read(store, wearOs = false))
    }

    @Test
    fun explicitPreferenceOverridesThePerTargetDefaultAndPersists() {
        val store = MemoryStore()
        assertFalse(ReducedMotionPreference.write(store, enabled = false))
        assertFalse(ReducedMotionPreference.read(store, wearOs = true))
        assertTrue(ReducedMotionPreference.write(store, enabled = true))
        assertTrue(ReducedMotionPreference.read(store, wearOs = false))
    }

    @Test
    fun malformedPreferenceFallsBackToThePerTargetDefault() {
        val store = MemoryStore().apply { put("reducedMotion", "yes") }
        assertTrue(ReducedMotionPreference.read(store, wearOs = true))
        assertFalse(ReducedMotionPreference.read(store, wearOs = false))
    }
}
