package com.bxane.nuvx.ui

import com.bxane.nuvx.core.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccentPalettePreferenceTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    @Test
    fun normalizesCustomHexAndRejectsMalformedValues() {
        assertEquals("#FFFFFF", AccentPalettePreference.DEFAULT)
        assertEquals("#FFFFFF", AccentPalettePreference.read(MemoryStore()))
        assertEquals("#A1B2C3", AccentPalettePreference.normalize(" #a1b2c3 "))
        assertEquals(AccentPalettePreference.DEFAULT, AccentPalettePreference.normalize("#12FG45"))
        assertTrue(AccentPalettePreference.isValid("#12aB45"))
        assertFalse(AccentPalettePreference.isValid("#12AB4"))
    }

    @Test
    fun generatedPaletteIsDeterministicAndContainsFourValidSwatches() {
        val palette = AccentPalettePreference.generate(seed = -713)
        assertEquals(palette, AccentPalettePreference.generate(seed = -713))
        assertEquals(4, palette.size)
        assertEquals(4, palette.distinct().size)
        assertTrue(palette.all(AccentPalettePreference::isValid))
    }

    @Test
    fun customAccentAndGeneratedSwatchesPersistAcrossAppRestart() {
        val store = MemoryStore()
        val generated = AccentPalettePreference.saveSwatches(store, AccentPalettePreference.generate(seed = 42))
        assertEquals(generated, AccentPalettePreference.readSwatches(store))

        assertEquals("#010203", AccentPalettePreference.save(store, " #010203 "))
        assertEquals("#010203", AccentPalettePreference.read(store))
        assertEquals(generated + "#010203", AccentPalettePreference.readSwatches(store))
    }

    @Test
    fun generatedPaletteStaysDraftUntilApplied() {
        val store = MemoryStore()
        val originalHex = AccentPalettePreference.read(store)
        val originalSwatches = AccentPalettePreference.readSwatches(store)
        val generated = AccentPaletteDraft(originalHex, originalSwatches).generate(seed = 42)

        assertEquals(originalHex, AccentPalettePreference.read(store))
        assertEquals(originalSwatches, AccentPalettePreference.readSwatches(store))
        assertEquals(generated.swatches.first(), generated.selectedHex)

        val custom = generated.copy(selectedHex = "#010203")
        assertEquals("#010203", custom.apply(store))
        assertEquals("#010203", AccentPalettePreference.read(store))
        assertEquals(generated.swatches + "#010203", AccentPalettePreference.readSwatches(store))
    }
}
