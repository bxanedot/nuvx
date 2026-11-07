package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoQualityPreferenceTest {
    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class VideoBackend : MusicBackend, VideoCapableBackend {
        override val id = BackendId.INNERTUBEX
        var requestedHeight: Int? = null

        override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
        override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
        override suspend fun stream(videoId: String): AudioStream? = null
        override suspend fun video(videoId: String, maxHeight: Int?): VideoStream {
            requestedHeight = maxHeight
            return VideoStream(videoId, "https://rr1---sn.googlevideo.com/audio", "https://rr1---sn.googlevideo.com/video",
                emptyMap(), 720, 1280, "video/mp4", "audio/mp4", "avc1", listOf(360, 720), id)
        }
    }

    @Test
    fun preferenceDefaultsToAutoAndInvalidValuesNormalizeSafely() {
        val store = MemoryStore()

        assertEquals(VideoQualityPreference.AUTO, VideoQualityPreference.read(store))
        store.put("videoQuality", "8k")
        assertEquals(VideoQualityPreference.AUTO, VideoQualityPreference.read(store))
        assertEquals(null, VideoQualityPreference.AUTO.maxHeight)
        assertEquals(360, VideoQualityPreference.DATA_SAVER.maxHeight)
        assertEquals(4320, VideoQualityPreference.HIGH_QUALITY.maxHeight)
    }

    @Test
    fun routerPersistsAndUsesQualityCapUnlessExplicitHeightIsPassed() = runTest {
        val store = MemoryStore().apply { put("videoQuality", "DATA_SAVER") }
        val backend = VideoBackend()
        val router = BackendRouter(mapOf(BackendId.INNERTUBEX to backend)).bindTo(store)

        router.resolveVideo("abcdefghijk")
        assertEquals(360, backend.requestedHeight)

        router.chooseVideoQuality(VideoQualityPreference.HIGH_QUALITY, store)
        router.resolveVideo("abcdefghijk")
        assertEquals(4320, backend.requestedHeight)
        assertEquals("HIGH_QUALITY", store.get("videoQuality"))

        router.resolveVideo("abcdefghijk", maxHeight = 720)
        assertEquals(720, backend.requestedHeight)
    }
}
