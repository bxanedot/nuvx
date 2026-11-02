package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun recentFirstDedupedAndPersisted() {
        val store = MapStore()
        History(store).apply {
            played(Track("a", "A")); played(Track("b", "B")); played(Track("a", "A"))
            searched(" Daft Punk "); searched("lofi"); searched("daft punk"); searched("   ")
        }
        val reloaded = History(store)
        assertEquals(listOf("a", "b"), reloaded.played.value.map { it.videoId })
        assertEquals(listOf("daft punk", "lofi"), reloaded.searches.value)
        reloaded.forgetSearch("lofi")
        assertEquals(listOf("daft punk"), History(store).searches.value)
        reloaded.clearSearches()
        assertEquals(emptyList(), History(store).searches.value)
    }

    @Test fun corruptStorageIsIgnored() {
        val store = MapStore().apply { map["history.played"] = "{not json" }
        assertEquals(emptyList(), History(store).played.value)
    }

    @Test fun capped() {
        val h = History(MapStore())
        repeat(60) { h.played(Track("id$it", "t")) }
        assertEquals(50, h.played.value.size)
        assertEquals("id59", h.played.value.first().videoId)
    }
}
