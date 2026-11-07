package com.bxane.nuvx.core

import com.metrolist.innertubex.extraction.AudioQuality
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioQualityPreferenceTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class QualityBackend : MusicBackend {
        override val id = BackendId.INNERTUBEX
        var requestedQuality: AudioQualityPreference? = null

        override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
        override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
        override suspend fun stream(videoId: String): AudioStream? = error("quality-aware stream method expected")
        override suspend fun stream(videoId: String, quality: AudioQualityPreference): AudioStream {
            requestedQuality = quality
            return AudioStream(videoId, "https://example.test/audio", emptyMap(), "audio/webm", 128, null, id)
        }
    }

    @Test
    fun qualityPreferenceDefaultsSafelyAndPersistsSupportedValues() {
        val store = MemoryStore()

        assertEquals(AudioQualityPreference.HIGH, AudioQualityPreference.read(store))
        assertEquals(AudioQualityPreference.HIGH, AudioQualityPreference.read(MemoryStore().apply { put("audioQuality", "lossless") }))
        assertEquals("Auto", AudioQualityPreference.AUTO.label)
        assertEquals("Low", AudioQualityPreference.LOW.label)
        assertEquals("High", AudioQualityPreference.HIGH.label)

        AudioQualityPreference.LOW.save(store)

        assertEquals(AudioQualityPreference.LOW, AudioQualityPreference.read(store))
    }

    @Test
    fun routerPassesSavedQualityToSupportedBackend() = runTest {
        val store = MemoryStore().apply { put("audioQuality", "LOW") }
        val backend = QualityBackend()
        val router = BackendRouter(mapOf(BackendId.INNERTUBEX to backend)).bindTo(store)

        router.stream("song-id")

        assertEquals(AudioQualityPreference.LOW, backend.requestedQuality)
        router.chooseAudioQuality(AudioQualityPreference.AUTO, store)
        router.stream("next-song")
        assertEquals(AudioQualityPreference.AUTO, backend.requestedQuality)
        assertEquals("AUTO", store.get("audioQuality"))
    }

    @Test
    fun qualityChoicesMapOnlyToSupportedExtractorModes() {
        assertEquals(AudioQuality.AUTO, AudioQualityPreference.AUTO.toExtractorQuality())
        assertEquals(AudioQuality.LOW, AudioQualityPreference.LOW.toExtractorQuality())
        assertEquals(AudioQuality.HIGH, AudioQualityPreference.HIGH.toExtractorQuality())
    }
}
