package com.bxane.nuvx.ui

import com.bxane.nuvx.core.KeyValueStore

internal object ThemeModePreference {
    private const val KEY = "darkMode"

    fun read(store: KeyValueStore): Boolean = store.get(KEY)?.toBooleanStrictOrNull() ?: false

    fun write(store: KeyValueStore, enabled: Boolean): Boolean = enabled.also {
        store.put(KEY, it.toString())
    }
}
