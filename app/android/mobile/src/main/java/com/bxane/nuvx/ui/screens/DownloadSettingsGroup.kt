package com.bxane.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.ui.icons.SfIcons

@Composable
internal fun DownloadSettingsGroup(
    search: SettingsSearch,
    downloadQuality: DownloadQuality,
    wifiOnlyDownloads: Boolean,
    metered: Boolean?,
    exportDownloads: Boolean,
    syncDownloadedPlaylists: Boolean,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
        val downloadQualityTitle = stringResource(R.string.download_quality)
        row(downloadQualityTitle, "offline", "lossless") {
            SettingsRow(
                icon = SfIcons.Download,
                title = downloadQualityTitle,
                subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                value = downloadQuality.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.DOWNLOAD_QUALITY) },
            )
        }
        val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
        row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
            SettingsSubRow(
                title = downloadWifiOnlyTitle,
                checked = wifiOnlyDownloads,
                onCheckedChange = AppSettings::setWifiOnlyDownloads,
                badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
            )
        }
        val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
        row(exportDownloadsTitle, "music folder") {
            SettingsSubRow(
                title = exportDownloadsTitle,
                checked = exportDownloads,
                onCheckedChange = AppSettings::setExportDownloads,
                subtitle = "Music/nuvx".takeIf { exportDownloads },
            )
        }
        val syncPlaylistsTitle = stringResource(R.string.sync_downloaded_playlists)
        row(syncPlaylistsTitle, "playlist", "new songs", "background", "automatic") {
            SettingsSubRow(
                title = syncPlaylistsTitle,
                checked = syncDownloadedPlaylists,
                onCheckedChange = AppSettings::setSyncDownloadedPlaylists,
                subtitle = stringResource(R.string.sync_downloaded_playlists_subtitle),
            )
        }
    }
}
