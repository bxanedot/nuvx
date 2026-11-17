package com.licht.nuvx.data.settings

import android.content.SharedPreferences
import com.licht.nuvx.playback.EqLayout
import com.licht.nuvx.playback.EqualizerPreset
import kotlinx.coroutines.flow.MutableStateFlow

internal class EqualizerSettingsController {
    private lateinit var prefs: SharedPreferences

    val enabled = MutableStateFlow(false)
    val mode = MutableStateFlow(EqualizerMode.DYNAMIC)
    val toneX = MutableStateFlow(0)
    val toneY = MutableStateFlow(0)
    val focused = MutableStateFlow(false)
    val balance = MutableStateFlow(0f)
    val bands = MutableStateFlow(EqualizerPreset.FLAT.bands)
    val preset = MutableStateFlow(EqualizerPreset.FLAT)

    // The output the EQ belongs to; each output remembers its own settings.
    private var device: String? = null

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        enabled.value = prefs.getBoolean(KEY_EQ_ENABLED, false)
        mode.value = runCatching {
            EqualizerMode.valueOf(prefs.getString(KEY_EQ_MODE, null) ?: EqualizerMode.DYNAMIC.name)
        }.getOrDefault(EqualizerMode.DYNAMIC)
        toneX.value = prefs.getInt(KEY_EQ_TONE_X, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        toneY.value = prefs.getInt(KEY_EQ_TONE_Y, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        focused.value = prefs.getBoolean(KEY_EQ_FOCUSED, false)
        balance.value = prefs.getFloat(KEY_EQ_BALANCE, 0f).coerceIn(-1f, 1f)
        bands.value = readBands()
        preset.value = EqualizerPreset.matching(bands.value)
    }

    /**
     * Per-device EQ: on a new output, load the settings it was last used with. An output without any keeps
     * the current settings. Every change is saved for the current output as it happens.
     */
    fun onOutputDevice(key: String) {
        if (key == device) return
        device = key
        val saved = prefs.getString(PROFILE_PREFIX + key, null)?.split('|') ?: return remember()
        runCatching {
            setEnabled(saved[0].toBooleanStrict())
            setMode(EqualizerMode.valueOf(saved[1]))
            setTone(saved[2].toInt(), saved[3].toInt())
            setFocused(saved[4].toBooleanStrict())
            setBalance(saved[5].toFloat())
            setBands(saved[6].split(';').map(String::toFloat))
        }
    }

    private fun remember() {
        val key = device ?: return
        val snapshot = listOf(enabled.value, mode.value.name, toneX.value, toneY.value, focused.value, balance.value, bands.value.joinToString(";"))
        prefs.edit().putString(PROFILE_PREFIX + key, snapshot.joinToString("|")).apply()
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
        remember()
    }

    fun setMode(value: EqualizerMode) {
        mode.value = value
        prefs.edit().putString(KEY_EQ_MODE, value.name).apply()
        remember()
    }

    fun setTone(x: Int, y: Int) {
        val steps = EqLayout.TONE_STEPS
        val clampedX = x.coerceIn(-steps, steps)
        val clampedY = y.coerceIn(-steps, steps)
        toneX.value = clampedX
        toneY.value = clampedY
        prefs.edit().putInt(KEY_EQ_TONE_X, clampedX).putInt(KEY_EQ_TONE_Y, clampedY).apply()
        remember()
    }

    fun setFocused(value: Boolean) {
        focused.value = value
        prefs.edit().putBoolean(KEY_EQ_FOCUSED, value).apply()
        remember()
    }

    fun setBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        balance.value = clamped
        prefs.edit().putFloat(KEY_EQ_BALANCE, clamped).apply()
        remember()
    }

    fun setBands(values: List<Float>) {
        val clamped = List(EqLayout.MANUAL_COUNT) {
            values.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
        bands.value = clamped
        preset.value = EqualizerPreset.matching(clamped)
        prefs.edit().putString(KEY_EQ_BANDS, clamped.joinToString(",")).apply()
        remember()
    }

    fun setPreset(value: EqualizerPreset) {
        if (value == EqualizerPreset.CUSTOM) return
        setBands(value.bands)
    }

    private fun readBands(): List<Float> {
        val stored = prefs.getString(KEY_EQ_BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            .orEmpty()
        return List(EqLayout.MANUAL_COUNT) {
            stored.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
    }

    private companion object {
        const val KEY_EQ_ENABLED = "equalizer_enabled"
        const val KEY_EQ_MODE = "equalizer_mode"
        const val KEY_EQ_TONE_X = "equalizer_tone_x"
        const val KEY_EQ_TONE_Y = "equalizer_tone_y"
        const val KEY_EQ_FOCUSED = "equalizer_focused"
        const val KEY_EQ_BALANCE = "equalizer_balance"
        const val KEY_EQ_BANDS = "equalizer_bands"
        const val PROFILE_PREFIX = "equalizer_profile:"
    }
}
