package com.bxane.nuvx.core

enum class AudioQualityPreference(val label: String) {
    AUTO("Auto"), LOW("Low"), HIGH("High");

    fun save(store: KeyValueStore) = store.put(KEY, name)

    companion object {
        private const val KEY = "audioQuality"

        fun read(store: KeyValueStore) = entries.firstOrNull { it.name == store.get(KEY) } ?: HIGH
    }
}
