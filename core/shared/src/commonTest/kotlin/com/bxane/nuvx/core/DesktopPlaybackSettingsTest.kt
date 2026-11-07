package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackSettingsTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun volumeBarStartsVisibleAndHidePreferenceSurvivesReload() {
        val store = MemoryStore()

        assertFalse(DesktopPlaybackSettings.load(store).hideVolumeBar)
        DesktopPlaybackSettings(hideVolumeBar = true).save(store)
        assertTrue(DesktopPlaybackSettings.load(store).hideVolumeBar)
        DesktopPlaybackSettings(hideVolumeBar = false).save(store)
        assertFalse(DesktopPlaybackSettings.load(store).hideVolumeBar)
    }

    @Test
    fun audioOutputDefaultsToSystemAndSelectionSurvivesReload() {
        val store = MemoryStore()

        assertTrue(DesktopPlaybackSettings.load(store).audioOutput.isSystemDefault)
        DesktopPlaybackSettings(audioOutput = DesktopAudioOutputSelection("mmdevice", "headphones")).save(store)

        assertEquals(DesktopAudioOutputSelection("mmdevice", "headphones"), DesktopPlaybackSettings.load(store).audioOutput)
    }

    @Test
    fun incompleteAudioOutputSelectionFallsBackToSystemDefault() {
        val store = MemoryStore()
        store.put("desktopAudioOutputName", "mmdevice")

        assertTrue(DesktopAudioOutputSelection.load(store).isSystemDefault)
    }

    @Test
    fun crossfadeDefaultsOffAndPersistsSupportedDuration() {
        val store = MemoryStore()

        assertEquals(0, DesktopPlaybackSettings.load(store).crossfadeSeconds)
        DesktopPlaybackSettings(crossfadeSeconds = 5).save(store)
        assertEquals(5, DesktopPlaybackSettings.load(store).crossfadeSeconds)
    }

    @Test
    fun unsupportedCrossfadeDurationFallsBackToOff() {
        val store = MemoryStore().apply { put("desktopCrossfadeSeconds", "7") }

        assertEquals(0, DesktopPlaybackSettings.load(store).crossfadeSeconds)
    }
}
