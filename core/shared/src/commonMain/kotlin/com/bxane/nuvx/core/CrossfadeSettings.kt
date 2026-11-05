package com.bxane.nuvx.core

object CrossfadeSettings {
    const val STORAGE_KEY = "androidCrossfadeSeconds"

    fun read(store: KeyValueStore): Int =
        CrossfadeAudio.normalizeDurationSeconds(store.get(STORAGE_KEY)?.toIntOrNull() ?: 0)

    fun write(store: KeyValueStore, seconds: Int): Int = CrossfadeAudio.normalizeDurationSeconds(seconds).also {
        store.put(STORAGE_KEY, it.toString())
    }
}
