package com.bxane.nuvx.ui

import com.bxane.nuvx.core.KeyValueStore

internal object ReducedMotionPreference {
    private const val KEY = "reducedMotion"

    fun read(store: KeyValueStore, wearOs: Boolean): Boolean =
        store.get(KEY)?.toBooleanStrictOrNull() ?: wearOs

    fun write(store: KeyValueStore, enabled: Boolean): Boolean = enabled.also {
        store.put(KEY, it.toString())
    }
}
