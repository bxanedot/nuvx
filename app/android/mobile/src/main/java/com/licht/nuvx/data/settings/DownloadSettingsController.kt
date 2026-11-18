package com.licht.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class DownloadSettingsController {
    private lateinit var prefs: SharedPreferences

    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)
    val wifiOnlyDownloads = MutableStateFlow(true)
    val exportDownloads = MutableStateFlow(false)
    val syncDownloadedPlaylists = MutableStateFlow(false)
    val downloadLimitGb = MutableStateFlow(0)

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
        downloadLimitGb.value = prefs.getInt(KEY_DOWNLOAD_LIMIT_GB, 0).takeIf { it in DOWNLOAD_LIMIT_CHOICES_GB } ?: 0
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

    fun setDownloadLimitGb(value: Int) {
        val safe = value.takeIf { it in DOWNLOAD_LIMIT_CHOICES_GB } ?: 0
        downloadLimitGb.value = safe
        prefs.edit().putInt(KEY_DOWNLOAD_LIMIT_GB, safe).apply()
    }

    private companion object {
        const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
        const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        const val KEY_EXPORT_DOWNLOADS = "export_downloads"
        const val KEY_SYNC_DOWNLOADED_PLAYLISTS = "sync_downloaded_playlists"
        const val KEY_DOWNLOAD_LIMIT_GB = "download_limit_gb"
    }
}

/** 0 means no limit. */
internal val DOWNLOAD_LIMIT_CHOICES_GB = listOf(0, 2, 5, 10, 20, 50)
