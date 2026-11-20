package com.licht.nuvx.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import java.io.File

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.licht.nuvx.R
import com.licht.nuvx.data.settings.AppSettings
import com.licht.nuvx.data.settings.DownloadQuality
import com.licht.nuvx.download.DownloadStore
import com.licht.nuvx.ui.icons.SfIcons

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
    val context = LocalContext.current
    var cleanupPreview by remember { mutableStateOf<DownloadStore.CleanupReport?>(null) }
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
        val limitTitle = stringResource(R.string.download_limit)
        val limitGb by AppSettings.downloadLimitGb.collectAsStateWithLifecycle()
        row(limitTitle, "storage", "space", "quota", "size", "gb") {
            SettingsRow(
                icon = SfIcons.Download,
                title = limitTitle,
                value = downloadLimitLabel(limitGb),
                onClick = { onOpenChoice(SettingsChoiceKind.DOWNLOAD_LIMIT) },
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
        val cleanupTitle = stringResource(R.string.download_cleanup_title)
        row(cleanupTitle, "temporary", "incomplete", "storage", "cleanup") {
            SettingsRow(
                icon = SfIcons.Download,
                title = cleanupTitle,
                subtitle = stringResource(R.string.download_cleanup_desc),
                onClick = {
                    cleanupPreview = DownloadStore.cleanupPrivateParts(
                        File(context.filesDir, "downloads"),
                        execute = false,
                    )
                },
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
    cleanupPreview?.let { planned ->
        AlertDialog(
            onDismissRequest = { cleanupPreview = null },
            title = { Text(stringResource(R.string.download_cleanup_title)) },
            text = {
                Text(stringResource(
                    R.string.download_cleanup_preview,
                    planned.files,
                    (planned.bytes + 1_048_575) / 1_048_576,
                ))
            },
            confirmButton = {
                TextButton(
                    enabled = planned.files > 0,
                    onClick = {
                        val removed = DownloadStore.cleanupPrivateParts(
                            File(context.filesDir, "downloads"),
                            execute = true,
                        )
                        cleanupPreview = null
                        Toast.makeText(context, context.getString(
                            R.string.download_cleanup_done,
                            removed.files,
                            (removed.bytes + 1_048_575) / 1_048_576,
                        ), Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.download_cleanup_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { cleanupPreview = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun downloadLimitLabel(gb: Int): String = if (gb == 0) stringResource(R.string.off) else "$gb GB"
