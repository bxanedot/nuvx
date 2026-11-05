package com.bxane.nuvx.core

data class RadioSettings(val avoidRepeats: Boolean = true) {
    fun save(store: KeyValueStore) = store.put(KEY_AVOID_REPEATS, avoidRepeats.toString())

    companion object {
        private const val KEY_AVOID_REPEATS = "radioAvoidRepeats"

        fun load(store: KeyValueStore) = RadioSettings(avoidRepeats = store.get(KEY_AVOID_REPEATS) != "false")
    }
}
