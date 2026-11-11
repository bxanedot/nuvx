package com.bxane.nuvx.data.listentogether

import android.content.SharedPreferences
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal class PartyKickLedger(
    private val preferences: () -> SharedPreferences,
    private val json: Json,
    private val key: String,
    private val blockMs: Long,
) {
    fun contains(code: String): Boolean = recentKicks().containsKey(code)

    fun record(code: String) {
        val normalised = code.filter { it.isLetterOrDigit() }.uppercase()
        if (normalised.isEmpty()) return
        writeKicks(recentKicks() + (normalised to System.currentTimeMillis() + blockMs))
    }

    private fun recentKicks(): Map<String, Long> {
        val preferences = preferences()
        val raw = preferences.getString(key, null) ?: return emptyMap()
        val stored = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        }.getOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        val live = stored.filterValues { it > now }
        if (live.size != stored.size) writeKicks(live, preferences)
        return live
    }

    private fun writeKicks(entries: Map<String, Long>, preferences: SharedPreferences = this.preferences()) {
        if (entries.isEmpty()) {
            preferences.edit().remove(key).apply()
            return
        }
        val encoded = json.encodeToString(
            MapSerializer(String.serializer(), Long.serializer()),
            entries,
        )
        preferences.edit().putString(key, encoded).apply()
    }
}
