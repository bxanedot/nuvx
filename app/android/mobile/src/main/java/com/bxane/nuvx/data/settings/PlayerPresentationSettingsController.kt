package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class PlayerPresentationSettingsController {
    private lateinit var prefs: SharedPreferences

    val animatedCanvas = MutableStateFlow(true)
    val canvasOverCellular = MutableStateFlow(false)
    val spotifyCanvasAutoHide = MutableStateFlow(true)
    val prioritizeSpotifyCanvas = MutableStateFlow(false)
    val signatureApiEnabled = MutableStateFlow(false)
    val signatureApiUrl = MutableStateFlow("")
    val fullBleedArtwork = MutableStateFlow(true)
    val fullBleedDetailArtwork = MutableStateFlow(false)
    val playerTheme = MutableStateFlow(PlayerTheme.DEFAULT)
    val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        canvasOverCellular.value = prefs.getBoolean(KEY_CANVAS_OVER_CELLULAR, false)
        spotifyCanvasAutoHide.value = prefs.getBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, true)
        prioritizeSpotifyCanvas.value = prefs.getBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, false)
        signatureApiEnabled.value = prefs.getBoolean(KEY_SIGNATURE_API_ENABLED, false)
        signatureApiUrl.value = normalizeSignatureApiUrl(prefs.getString(KEY_SIGNATURE_API_URL, "").orEmpty())
        fullBleedArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_ARTWORK, true)
        fullBleedDetailArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_DETAIL_ARTWORK, false)
        playerTheme.value = runCatching {
            PlayerTheme.valueOf(prefs.getString(KEY_PLAYER_THEME, null).orEmpty())
        }.getOrElse {
            if (prefs.getBoolean(KEY_LEGACY_MESH_GRADIENT, false)) PlayerTheme.LEGACY else PlayerTheme.DEFAULT
        }
        lastPlayerScreen.value = runCatching {
            LastPlayerScreen.valueOf(
                prefs.getString(KEY_LAST_PLAYER_SCREEN, null) ?: LastPlayerScreen.MAIN.name,
            )
        }.getOrDefault(LastPlayerScreen.MAIN)
    }

    fun normalizeSignatureApiUrl(raw: String): String = raw.trim().trimEnd('/').take(500)

    fun setAnimatedCanvas(value: Boolean) {
        animatedCanvas.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_CANVAS, value).apply()
    }

    fun setCanvasOverCellular(value: Boolean) {
        canvasOverCellular.value = value
        prefs.edit().putBoolean(KEY_CANVAS_OVER_CELLULAR, value).apply()
    }

    fun setSpotifyCanvasAutoHide(value: Boolean) {
        spotifyCanvasAutoHide.value = value
        prefs.edit().putBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, value).apply()
    }

    fun setPrioritizeSpotifyCanvas(value: Boolean) {
        prioritizeSpotifyCanvas.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, value).apply()
    }

    fun setSignatureApiEnabled(value: Boolean) {
        signatureApiEnabled.value = value
        prefs.edit().putBoolean(KEY_SIGNATURE_API_ENABLED, value).apply()
    }

    fun setSignatureApiUrl(value: String) {
        val normalized = normalizeSignatureApiUrl(value)
        signatureApiUrl.value = normalized
        prefs.edit().putString(KEY_SIGNATURE_API_URL, normalized).apply()
    }

    fun setFullBleedArtwork(value: Boolean) {
        fullBleedArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_ARTWORK, value).apply()
    }

    fun setFullBleedDetailArtwork(value: Boolean) {
        fullBleedDetailArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_DETAIL_ARTWORK, value).apply()
    }

    fun setPlayerTheme(value: PlayerTheme) {
        playerTheme.value = value
        prefs.edit().putString(KEY_PLAYER_THEME, value.name).apply()
    }

    fun setLastPlayerScreen(value: LastPlayerScreen) {
        if (lastPlayerScreen.value == value) return
        lastPlayerScreen.value = value
        prefs.edit().putString(KEY_LAST_PLAYER_SCREEN, value.name).apply()
    }

    private companion object {
        const val KEY_ANIMATED_CANVAS = "animated_canvas"
        const val KEY_CANVAS_OVER_CELLULAR = "canvas_over_cellular"
        const val KEY_SPOTIFY_CANVAS_AUTO_HIDE = "spotify_canvas_auto_hide"
        const val KEY_PRIORITIZE_SPOTIFY_CANVAS = "prioritize_spotify_canvas"
        const val KEY_SIGNATURE_API_ENABLED = "signature_api_enabled"
        const val KEY_SIGNATURE_API_URL = "signature_api_url"
        const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
        const val KEY_FULL_BLEED_DETAIL_ARTWORK = "full_bleed_detail_artwork"
        const val KEY_PLAYER_THEME = "player_theme"
        const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
        const val KEY_LAST_PLAYER_SCREEN = "last_player_screen"
    }
}
