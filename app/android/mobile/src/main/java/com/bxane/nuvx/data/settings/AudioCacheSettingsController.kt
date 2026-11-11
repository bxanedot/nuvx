package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class AudioCacheSettingsController(
    private val defaultLimitBytes: Long,
    private val maxLimitBytes: Long,
    private val unlimitedLimitBytes: Long,
) {
    private lateinit var prefs: SharedPreferences

    val cacheLimitBytes = MutableStateFlow(defaultLimitBytes)
    val showCacheFolder = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        cacheLimitBytes.value = clampCacheLimit(prefs.getLong(KEY_CACHE_LIMIT, defaultLimitBytes))
        showCacheFolder.value = prefs.getBoolean(KEY_SHOW_CACHE_FOLDER, false)
    }

    fun setCacheLimitBytes(value: Long) {
        val clamped = clampCacheLimit(value)
        cacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    fun setShowCacheFolder(value: Boolean) {
        showCacheFolder.value = value
        prefs.edit().putBoolean(KEY_SHOW_CACHE_FOLDER, value).apply()
    }

    private fun clampCacheLimit(value: Long): Long =
        if (value > maxLimitBytes) unlimitedLimitBytes else value.coerceAtLeast(defaultLimitBytes)

    private companion object {
        const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
        const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"
    }
}
