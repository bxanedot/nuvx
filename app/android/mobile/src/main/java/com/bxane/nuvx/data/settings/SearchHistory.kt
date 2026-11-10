package com.bxane.nuvx.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.bxane.nuvx.data.model.SearchHistoryEntity
import com.bxane.nuvx.data.model.withCorrectedType

object SearchHistory {

    private const val MAX_ENTRIES = 20

    private lateinit var prefs: SharedPreferences
    private val json = Json
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())
    private val addonSerializer = ListSerializer(String.serializer())

    private val _recent = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())

    val recent: StateFlow<List<SearchHistoryEntity>> = _recent.asStateFlow()

    private val _addonRecent = MutableStateFlow<List<String>>(emptyList())

    val addonRecent: StateFlow<List<String>> = _addonRecent.asStateFlow()

    fun recordAddonQuery(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        saveAddon((listOf(text) + _addonRecent.value.filterNot { it.equals(text, ignoreCase = true) }).take(MAX_ENTRIES))
    }

    fun removeAddonQuery(query: String) = saveAddon(_addonRecent.value.filterNot { it == query })

    fun clearAddonQueries() = saveAddon(emptyList())

    private fun saveAddon(value: List<String>) {
        _addonRecent.value = value
        prefs.edit().putString(KEY_ADDON_HISTORY, json.encodeToString(addonSerializer, value)).apply()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nuvx_settings", Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        if (!this::prefs.isInitialized) return
        _recent.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_HISTORY, null) ?: "[]")
                .map { it.withCorrectedType() }
        }.getOrDefault(emptyList())
        _addonRecent.value = runCatching {
            json.decodeFromString(addonSerializer, prefs.getString(KEY_ADDON_HISTORY, null) ?: "[]")
        }.getOrDefault(emptyList())
    }

    fun record(entity: SearchHistoryEntity) {
        val deduped = _recent.value.filterNot { it.id.equals(entity.id, ignoreCase = true) }
        save((listOf(entity) + deduped).take(MAX_ENTRIES))
    }

    fun remove(id: String) {
        save(_recent.value.filterNot { it.id.equals(id, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(serializer, value)).apply()
    }

    private const val KEY_HISTORY = "search_history"
    private const val KEY_ADDON_HISTORY = "addon_search_history"
}
