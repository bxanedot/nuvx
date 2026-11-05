package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTextSizePreferenceTest {
    private class Store : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun defaultsToStandardAndRecoversFromUnknownSavedValues() {
        val store = Store()

        assertEquals(LyricsTextSizePreference.STANDARD, LyricsTextSizePreference.read(store))
        store.put("lyricsTextSize", "not-a-size")
        assertEquals(LyricsTextSizePreference.STANDARD, LyricsTextSizePreference.read(store))
    }

    @Test
    fun persistsEachSupportedSize() {
        val store = Store()

        LyricsTextSizePreference.entries.forEach { size ->
            LyricsTextSizePreference.write(store, size)
            assertEquals(size, LyricsTextSizePreference.read(store))
        }
    }
}
