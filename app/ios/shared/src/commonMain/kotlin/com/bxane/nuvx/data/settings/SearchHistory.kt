package com.bxane.nuvx.data.settings


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json






object SearchHistory {


    private const val MAX_ENTRIES = 20
    private const val KEY_HISTORY = "search_history"

    private val settings = PlatformSettings
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    private val _recent = MutableStateFlow(load())

    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private fun load(): List<String> = runCatching {
        json.decodeFromString(serializer, settings.getString(KEY_HISTORY, "[]"))
    }.getOrDefault(emptyList())


    fun record(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        val deduped = _recent.value.filterNot { it.equals(term, ignoreCase = true) }
        save((listOf(term) + deduped).take(MAX_ENTRIES))
    }

    fun remove(query: String) {
        save(_recent.value.filterNot { it.equals(query, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun save(value: List<String>) {
        _recent.value = value
        settings.putString(KEY_HISTORY, json.encodeToString(serializer, value))
    }
}
