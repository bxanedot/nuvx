package com.bxane.nuvx.core

/** User-controlled lookup order and default for the registered lyric providers. */
data class LyricsProviderSettings(
    val order: List<String>,
    val disabled: Set<String> = emptySet(),
    val preferredSource: String?,
    val preferWordSynced: Boolean = true,
) {
    fun normalized(providerNames: List<String>): LyricsProviderSettings {
        val known = providerNames.toSet()
        val normalizedOrder = order.filter { it in known }.distinct() + providerNames.filterNot(order.toSet()::contains)
        val normalizedDisabled = disabled.intersect(known)
        val active = normalizedOrder.filterNot(normalizedDisabled::contains)
        return copy(order = normalizedOrder, disabled = normalizedDisabled,
            preferredSource = preferredSource?.takeIf(active::contains) ?: active.firstOrNull())
    }

    fun enabled(providerNames: List<String>): List<String> = normalized(providerNames).let { settings ->
        settings.order.filterNot(settings.disabled::contains)
    }

    fun save(store: KeyValueStore) {
        store.put(ORDER_KEY, order.joinToString("\n"))
        store.put(DISABLED_KEY, disabled.joinToString("\n"))
        store.put(DEFAULT_KEY, preferredSource)
        store.put(WORD_SYNC_KEY, preferWordSynced.toString())
    }

    companion object {
        private const val ORDER_KEY = "lyricsProviderOrder"
        private const val DISABLED_KEY = "lyricsProviderDisabled"
        private const val DEFAULT_KEY = "lyricsProviderDefault"
        private const val WORD_SYNC_KEY = "lyricsPreferWordSynced"

        fun load(store: KeyValueStore, providerNames: List<String>): LyricsProviderSettings {
            fun lines(key: String) = store.get(key).orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val storedOrder = lines(ORDER_KEY)
            return LyricsProviderSettings(
                order = storedOrder.ifEmpty { providerNames },
                disabled = lines(DISABLED_KEY).toSet(),
                preferredSource = store.get(DEFAULT_KEY),
                preferWordSynced = store.get(WORD_SYNC_KEY)?.toBooleanStrictOrNull() ?: true,
            ).normalized(providerNames)
        }
    }
}
