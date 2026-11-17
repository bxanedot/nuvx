package com.licht.nuvx

import com.licht.nuvx.data.settings.EqualizerSettingsController
import com.licht.nuvx.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerDeviceEqualizerTest {

    private fun bands(db: Float) = List(EqLayout.MANUAL_COUNT) { db }

    @Test
    fun eachOutputKeepsItsOwnEqualizer() {
        val prefs = FakeSharedPreferences()
        val eq = EqualizerSettingsController().apply { initialize(prefs); read() }

        eq.onOutputDevice("speaker")
        eq.setEnabled(true)
        eq.setBands(bands(3f))

        eq.onOutputDevice("8:Headphones")
        assertTrue("a new output starts from the current settings", eq.enabled.value)
        eq.setEnabled(false)
        eq.setBands(bands(-2f))

        eq.onOutputDevice("speaker")
        assertTrue(eq.enabled.value)
        assertEquals(bands(3f), eq.bands.value)

        eq.onOutputDevice("8:Headphones")
        assertFalse(eq.enabled.value)
        assertEquals(bands(-2f), eq.bands.value)
    }

    @Test
    fun aRestartReturnsToTheLatestSettingsOfThatOutput() {
        val prefs = FakeSharedPreferences()
        EqualizerSettingsController().apply { initialize(prefs); read(); onOutputDevice("speaker"); setBands(bands(4f)) }

        val again = EqualizerSettingsController().apply { initialize(prefs); read() }
        again.onOutputDevice("speaker")
        assertEquals(bands(4f), again.bands.value)
    }
}
