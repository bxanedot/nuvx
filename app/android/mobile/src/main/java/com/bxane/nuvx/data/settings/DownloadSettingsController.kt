package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class DownloadSettingsController {
    private lateinit var prefs: SharedPreferences

    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)
    val wifiOnlyDownloads = MutableStateFlow(true)
    val exportDownloads = MutableStateFlow(false)
    val syncDownloadedPlaylists = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        if (!prefs.contains(KEY_QUALITY_DOWNLOAD)) {
            prefs.edit().putString(KEY_QUALITY_DOWNLOAD, DownloadQuality.LOSSLESS.name).apply()
        }
        val stored = prefs.getString(KEY_QUALITY_DOWNLOAD, null)
        downloadQuality.value = stored
            ?.let { runCatching { DownloadQuality.valueOf(it) }.getOrDefault(DownloadQuality.LOSSLESS) }
            ?: DownloadQuality.LOSSLESS
        wifiOnlyDownloads.value = prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        exportDownloads.value = prefs.getBoolean(KEY_EXPORT_DOWNLOADS, false)
        syncDownloadedPlaylists.value = prefs.getBoolean(KEY_SYNC_DOWNLOADED_PLAYLISTS, false)
    }

    fun setDownloadQuality(value: DownloadQuality) {
        downloadQuality.value = value
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, value.name).apply()
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        wifiOnlyDownloads.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()
    }

    fun setExportDownloads(value: Boolean) {
        exportDownloads.value = value
        prefs.edit().putBoolean(KEY_EXPORT_DOWNLOADS, value).apply()
    }

    fun setSyncDownloadedPlaylists(value: Boolean) {
        syncDownloadedPlaylists.value = value
        prefs.edit().putBoolean(KEY_SYNC_DOWNLOADED_PLAYLISTS, value).apply()
    }

    private companion object {
        const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
        const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        const val KEY_EXPORT_DOWNLOADS = "export_downloads"
        const val KEY_SYNC_DOWNLOADED_PLAYLISTS = "sync_downloaded_playlists"
    }
}
