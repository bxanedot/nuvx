package com.licht.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.licht.nuvx.R
import com.licht.nuvx.data.settings.AppSettings
import com.licht.nuvx.ui.components.IosSwitch
import com.licht.nuvx.ui.icons.SfIcons

@Composable
internal fun YourDataSettingsGroup(
    search: SettingsSearch,
    replayGenres: Boolean,
    exportStatus: String?,
    importStatus: String?,
    onOpenReplay: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SearchableSettingsGroup(
        search,
        header = stringResource(R.string.your_data),
        footer = stringResource(R.string.your_data_footer),
    ) {
        val replayTitle = stringResource(R.string.replay)
        row(replayTitle, "stats", "history", "wrapped") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = replayTitle,
                subtitle = stringResource(R.string.replay_subtitle),
                onClick = onOpenReplay,
            )
        }
        val workOutGenresTitle = stringResource(R.string.work_out_genres)
        row(workOutGenresTitle, "genre", "replay") {
            SettingsRow(
                icon = SfIcons.LocalOffer,
                title = workOutGenresTitle,
                subtitle = if (replayGenres) {
                    stringResource(R.string.replay_genres_enabled_subtitle)
                } else {
                    stringResource(R.string.replay_genres_disabled_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = replayGenres,
                        onCheckedChange = AppSettings::setReplayGenres,
                    )
                },
                onClick = { AppSettings.setReplayGenres(!replayGenres) },
            )
        }
        val exportDataTitle = stringResource(R.string.export_data)
        row(exportDataTitle, "backup") {
            SettingsRow(
                icon = SfIcons.FileUpload,
                title = exportDataTitle,
                subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                onClick = onExport,
            )
        }
        val importDataTitle = stringResource(R.string.import_data)
        row(importDataTitle, "backup", "restore") {
            SettingsRow(
                icon = SfIcons.FileDownload,
                title = importDataTitle,
                subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                onClick = onImport,
            )
        }
        val diagnosticsTitle = stringResource(R.string.copy_diagnostics)
        row(diagnosticsTitle, "log", "debug", "bug", "report", "diagnostics", "support") {
            SettingsRow(
                icon = SfIcons.Bug,
                title = diagnosticsTitle,
                subtitle = stringResource(R.string.copy_diagnostics_subtitle),
                onClick = {
                    val text = com.licht.nuvx.data.TrackLog.recentProblems()
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(diagnosticsTitle, text))
                    val count = text.lineSequence().count { it.isNotEmpty() }
                    android.widget.Toast.makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.log_copied_line_count, count, count),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}
