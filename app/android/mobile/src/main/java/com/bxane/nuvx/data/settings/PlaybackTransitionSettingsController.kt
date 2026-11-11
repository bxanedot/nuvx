package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class PlaybackTransitionSettingsController {
    private lateinit var prefs: SharedPreferences

    val crossfadeSeconds = MutableStateFlow(0)
    val smartFadeEnabled = MutableStateFlow(false)
    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
    val skipSilence = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE, 0)
        smartFadeEnabled.value = prefs.getBoolean(KEY_SMART_FADE, false)
        automixPerformanceMode.value = runCatching {
            AutomixPerformanceMode.valueOf(
                prefs.getString(KEY_AUTOMIX_PERFORMANCE_MODE, null) ?: AutomixPerformanceMode.BALANCED.name,
            )
        }.getOrDefault(AutomixPerformanceMode.BALANCED)
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
    }

    fun setCrossfadeSeconds(value: Int) {
        crossfadeSeconds.value = value
        prefs.edit().putInt(KEY_CROSSFADE, value).apply()
    }

    fun setSmartFadeEnabled(value: Boolean) {
        smartFadeEnabled.value = value
        prefs.edit().putBoolean(KEY_SMART_FADE, value).apply()
    }

    fun setAutomixPerformanceMode(value: AutomixPerformanceMode) {
        automixPerformanceMode.value = value
        prefs.edit().putString(KEY_AUTOMIX_PERFORMANCE_MODE, value.name).apply()
    }

    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    private companion object {
        const val KEY_CROSSFADE = "crossfade_seconds"
        const val KEY_SMART_FADE = "smart_fade_enabled"
        const val KEY_AUTOMIX_PERFORMANCE_MODE = "automix_performance_mode"
        const val KEY_SKIP_SILENCE = "skip_silence"
    }
}
