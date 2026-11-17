package com.licht.nuvx.data.settings

import com.licht.nuvx.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeDefaultTest {

    private fun read(stored: String?): ThemeMode {
        val prefs = FakeSharedPreferences()
        if (stored != null) prefs.edit().putString("theme_mode", stored).commit()
        return AppearanceSettingsController().apply {
            initialize(prefs)
            readThemeAndFont()
        }.themeMode.value
    }

    @Test
    fun `a fresh install follows the system theme`() {
        assertEquals(ThemeMode.SYSTEM, AppearanceSettingsController().themeMode.value)
        assertEquals(ThemeMode.SYSTEM, read(null))
    }

    @Test
    fun `an explicit choice is kept and junk falls back to system`() {
        assertEquals(ThemeMode.LIGHT, read("LIGHT"))
        assertEquals(ThemeMode.DARK, read("DARK"))
        assertEquals(ThemeMode.SYSTEM, read("SEPIA"))
    }
}
