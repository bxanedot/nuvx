package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicBackendTest {
    @Test fun routerDoesNotSendLocalTrackIdsToRadioBackends() = runTest {
        var calls = 0
        val backend = object : MusicBackend {
            override val id = BackendId.INNERTUBEX
            override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
            override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
                calls++
                return RadioPage(emptyList(), null)
            }
            override suspend fun stream(videoId: String): AudioStream? = null
        }
        val router = BackendRouter(mapOf(backend.id to backend))

        assertEquals(RadioPage(emptyList(), null), router.radio("local:opaque-id"))
        assertEquals(0, calls)
        router.radio("abcdefghijk")
        assertEquals(1, calls)
    }
}
