package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsProviderSettingsTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private val providers = listOf("lrc.red", "LRCLIB", "Better Lyrics", "Unison")

    @Test fun freshSettingsKeepLrcRedFirstAndPreferWordTimedLyrics() {
        val settings = LyricsProviderSettings.load(MemoryStore(), providers)

        assertEquals(providers, settings.order)
        assertEquals("lrc.red", settings.preferredSource)
        assertTrue(settings.preferWordSynced)
        assertEquals(providers, settings.enabled(providers))
    }

    @Test fun loadDropsUnknownAndDuplicateNamesAndFallsBackFromDisabledDefault() {
        val store = MemoryStore().apply {
            put("lyricsProviderOrder", "missing\nLRCLIB\nLRCLIB")
            put("lyricsProviderDisabled", "missing\nLRCLIB")
            put("lyricsProviderDefault", "missing")
            put("lyricsPreferWordSynced", "false")
        }

        val settings = LyricsProviderSettings.load(store, providers)

        assertEquals(listOf("LRCLIB", "lrc.red", "Better Lyrics", "Unison"), settings.order)
        assertEquals(setOf("LRCLIB"), settings.disabled)
        assertEquals("lrc.red", settings.preferredSource)
        assertFalse(settings.preferWordSynced)
        assertEquals(listOf("lrc.red", "Better Lyrics", "Unison"), settings.enabled(providers))
    }

    @Test fun settingsRoundTripThroughTheExistingStore() {
        val store = MemoryStore()
        val expected = LyricsProviderSettings(
            order = listOf("Unison", "lrc.red", "LRCLIB", "Better Lyrics"),
            disabled = setOf("LRCLIB"),
            preferredSource = "Unison",
            preferWordSynced = false,
        )

        expected.save(store)

        assertEquals(expected, LyricsProviderSettings.load(store, providers))
    }
}
