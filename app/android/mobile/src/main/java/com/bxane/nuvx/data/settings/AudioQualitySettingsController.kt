package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class AudioQualitySettingsController(
    private val defaultUpgradeLengthSlackSeconds: Int,
    private val minUpgradeLengthSlackSeconds: Int,
    private val maxUpgradeLengthSlackSeconds: Int,
) {
    private lateinit var prefs: SharedPreferences

    val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)
    val upgradeLengthSlackSeconds = MutableStateFlow(defaultUpgradeLengthSlackSeconds)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        migrateSingleQuality()
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        upgradeLengthSlackSeconds.value = prefs.getInt(
            KEY_UPGRADE_LENGTH_SLACK_SECONDS,
            defaultUpgradeLengthSlackSeconds,
        ).coerceIn(minUpgradeLengthSlackSeconds, maxUpgradeLengthSlackSeconds)
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        audioQualityWifi.value = value
        prefs.edit().putString(KEY_QUALITY_WIFI, value.name).apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        audioQualityCellular.value = value
        prefs.edit().putString(KEY_QUALITY_CELLULAR, value.name).apply()
    }

    fun setUpgradeLengthSlackSeconds(value: Int) {
        val normalized = value.coerceIn(minUpgradeLengthSlackSeconds, maxUpgradeLengthSlackSeconds)
        upgradeLengthSlackSeconds.value = normalized
        prefs.edit().putInt(KEY_UPGRADE_LENGTH_SLACK_SECONDS, normalized).apply()
    }

    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.LOSSLESS
        return runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOSSLESS)
    }

    private companion object {
        const val KEY_QUALITY_LEGACY = "audio_quality"
        const val KEY_QUALITY_WIFI = "audio_quality_wifi"
        const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
        const val KEY_UPGRADE_LENGTH_SLACK_SECONDS = "upgrade_length_slack_seconds"
    }
}
