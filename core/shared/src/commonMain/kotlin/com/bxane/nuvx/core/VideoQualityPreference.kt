package com.bxane.nuvx.core

enum class VideoQualityPreference(val label: String, val maxHeight: Int?) {
    AUTO("Auto", null),
    DATA_SAVER("Data saver", 360),
    HIGH_QUALITY("High quality", 4320);

    fun save(store: KeyValueStore) = store.put(KEY, name)

    companion object {
        private const val KEY = "videoQuality"

        fun read(store: KeyValueStore): VideoQualityPreference =
            store.get(KEY)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: AUTO
    }
}
