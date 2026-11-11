package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class AppearanceSettingsController {
    private lateinit var prefs: SharedPreferences

    val themeMode = MutableStateFlow(ThemeMode.LIGHT)
    val appFont = MutableStateFlow(AppFont.DEFAULT)
    val reduceAnimation = MutableStateFlow(false)
    val highPerformanceMode = MutableStateFlow(false)
    val performanceRefreshRate = MutableStateFlow(DEFAULT_PERFORMANCE_REFRESH_RATE)
    val smartVersionAlignment = MutableStateFlow(true)
    val reduceDynamicBlur = MutableStateFlow(false)
    val disableBlendEffects = MutableStateFlow(false)
    val liquidGlass = MutableStateFlow(true)
    val glassPerformance = MutableStateFlow(true)
    val progressiveBlur = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun readThemeAndFont() {
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "LIGHT")
        }.getOrDefault(ThemeMode.LIGHT)
        appFont.value = runCatching {
            AppFont.valueOf(prefs.getString(KEY_APP_FONT, null) ?: AppFont.DEFAULT.name)
        }.getOrDefault(AppFont.DEFAULT)
    }

    fun readReduceAnimation() {
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
    }

    fun readPerformanceMode() {
        highPerformanceMode.value = prefs.getBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        performanceRefreshRate.value = normalizePerformanceRefreshRate(
            prefs.getInt(KEY_PERFORMANCE_REFRESH_RATE, DEFAULT_PERFORMANCE_REFRESH_RATE),
        )
    }

    fun readVisualEffects() {
        smartVersionAlignment.value = prefs.getBoolean(KEY_SMART_VERSION_ALIGNMENT, true)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        disableBlendEffects.value = prefs.getBoolean(KEY_DISABLE_BLEND_EFFECTS, false)
        liquidGlass.value = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        glassPerformance.value = prefs.getBoolean(KEY_GLASS_PERFORMANCE, true)
        progressiveBlur.value = prefs.getBoolean(KEY_PROGRESSIVE_BLUR, false)
    }

    fun applyPerformanceModeOverrides() {
        if (highPerformanceMode.value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setAppFont(value: AppFont) {
        appFont.value = value
        prefs.edit().putString(KEY_APP_FONT, value.name).apply()
    }

    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setSmartVersionAlignment(value: Boolean) {
        smartVersionAlignment.value = value
        prefs.edit().putBoolean(KEY_SMART_VERSION_ALIGNMENT, value).apply()
    }

    fun setDisableBlendEffects(value: Boolean) {
        disableBlendEffects.value = value
        prefs.edit().putBoolean(KEY_DISABLE_BLEND_EFFECTS, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_BLUR, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setLiquidGlass(value: Boolean) {
        liquidGlass.value = value
        prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
    }

    fun setGlassPerformance(value: Boolean) {
        glassPerformance.value = value
        prefs.edit().putBoolean(KEY_GLASS_PERFORMANCE, value).apply()
    }

    fun setProgressiveBlur(value: Boolean) {
        progressiveBlur.value = value
        prefs.edit().putBoolean(KEY_PROGRESSIVE_BLUR, value).apply()
    }

    fun setHighPerformanceMode(value: Boolean) {
        highPerformanceMode.value = value
        if (value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        val editor = prefs.edit().putBoolean(KEY_HIGH_PERFORMANCE_MODE, value)
        if (value) {
            editor.putBoolean(KEY_REDUCE_ANIMATION, false)
            editor.putBoolean(KEY_REDUCE_BLUR, false)
        }
        editor.apply()
    }

    fun setPerformanceRefreshRate(value: Int) {
        val normalized = normalizePerformanceRefreshRate(value)
        performanceRefreshRate.value = normalized
        prefs.edit().putInt(KEY_PERFORMANCE_REFRESH_RATE, normalized).apply()
    }

    private fun normalizePerformanceRefreshRate(value: Int): Int =
        value.takeIf { it in 50..240 } ?: DEFAULT_PERFORMANCE_REFRESH_RATE

    private companion object {
        const val DEFAULT_PERFORMANCE_REFRESH_RATE = 120
        const val KEY_THEME = "theme_mode"
        const val KEY_APP_FONT = "app_font"
        const val KEY_REDUCE_ANIMATION = "reduce_animation"
        const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
        const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
        const val KEY_SMART_VERSION_ALIGNMENT = "smart_version_alignment"
        const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
        const val KEY_DISABLE_BLEND_EFFECTS = "disable_blend_effects"
        const val KEY_LIQUID_GLASS = "liquid_glass"
        const val KEY_GLASS_PERFORMANCE = "glass_performance"
        const val KEY_PROGRESSIVE_BLUR = "progressive_blur"
    }
}
