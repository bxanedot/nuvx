package com.bxane.nuvx.core

enum class LyricsTextSizePreference(val label: String, val scale: Float) {
    SMALL("Small", 0.8f),
    STANDARD("Standard", 1f),
    LARGE("Large", 1.25f),
    EXTRA_LARGE("Extra large", 1.5f);

    fun save(store: KeyValueStore) = store.put(KEY, name)

    companion object {
        private const val KEY = "lyricsTextSize"
        val DEFAULT = STANDARD

        fun read(store: KeyValueStore): LyricsTextSizePreference =
            entries.firstOrNull { it.name == store.get(KEY) } ?: DEFAULT

        fun write(store: KeyValueStore, size: LyricsTextSizePreference): LyricsTextSizePreference = size.also { it.save(store) }
    }
}
