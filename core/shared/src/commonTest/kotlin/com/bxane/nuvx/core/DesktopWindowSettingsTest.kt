package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowSettingsTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun trayAndKeepPlayingDefaultOffAndPersist() {
        val store = MemoryStore()

        assertFalse(DesktopWindowSettings.load(store).showTrayIcon)
        assertFalse(DesktopWindowSettings.load(store).keepPlayingWhenClosed)
        DesktopWindowSettings(showTrayIcon = true, keepPlayingWhenClosed = true).save(store)
        assertTrue(DesktopWindowSettings.load(store).showTrayIcon)
        assertTrue(DesktopWindowSettings.load(store).keepPlayingWhenClosed)
    }

    @Test
    fun keepPlayingRequiresTrayIcon() {
        val store = MemoryStore()

        DesktopWindowSettings(keepPlayingWhenClosed = true).save(store)

        assertFalse(DesktopWindowSettings.load(store).keepPlayingWhenClosed)
    }
}
