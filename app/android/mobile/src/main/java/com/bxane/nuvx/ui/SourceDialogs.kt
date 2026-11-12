package com.bxane.nuvx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.ui.components.ChoiceAlert
import com.bxane.nuvx.ui.components.ConfirmationAlert
import com.bxane.nuvx.ui.components.InfoAlert
import com.bxane.nuvx.ui.screens.SourceEditorAlert
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun SourceConfigurationDialogs(
    hazeState: HazeState,
    editingSource: SourceConfig?,
    onEditSourceDismiss: () -> Unit,
    scope: CoroutineScope,
    choosingSourceQuality: SourceConfig?,
    onSourceQualityDismiss: () -> Unit,
    explainingOutdatedAddon: SourceConfig?,
    onOutdatedAddonDismiss: () -> Unit,
    confirmJioSaavn: Boolean,
    onJioSaavnDismiss: () -> Unit,
) {
    editingSource?.let { config ->
        SourceEditorAlert(
            hazeState = hazeState,
            config = config,
            onDismiss = onEditSourceDismiss,
            onSaved = onEditSourceDismiss,
            onDelete = {
                SourceRegistry.remove(config.id)
                onEditSourceDismiss()
            },
            scope = scope,
        )
    }

    choosingSourceQuality?.let { config ->
        ChoiceAlert(
            hazeState = hazeState,
            title = stringResource(R.string.audio_quality),
            message = null,
            options = config.qualityOptions,
            selected = config.activeQuality,
            label = { it?.label.orEmpty() },
            onSelect = { option ->
                SourceRegistry.config(config.id)?.let {
                    SourceRegistry.update(it.copy(pickedQuality = option?.value))
                }
                onSourceQualityDismiss()
            },
            onDismiss = onSourceQualityDismiss,
        )
    }

    explainingOutdatedAddon?.let { config ->
        InfoAlert(
            hazeState = hazeState,
            title = config.displayName,
            description = stringResource(R.string.source_needs_update),
            onDismiss = onOutdatedAddonDismiss,
        )
    }

    if (confirmJioSaavn) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.enable_jiosaavn),
            description = stringResource(R.string.jiosaavn_mismatch_warning),
            confirmLabel = stringResource(R.string.enable_anyway),
            onConfirm = {
                SourceRegistry.configs.value
                    .firstOrNull { it.kind == SourceKind.JIOSAAVN }
                    ?.let { SourceRegistry.setEnabled(it.id, true) }
                onJioSaavnDismiss()
            },
            onDismiss = onJioSaavnDismiss,
        )
    }
}

@Composable
internal fun AddonPlaybackAlerts(
    hazeState: HazeState,
    blockedAddonSong: Song?,
    onBlockedAddonDismiss: () -> Unit,
    addonLeftOutOfDownload: Pair<String, SourceRegistry.DownloadExclusion>?,
    inOwnWindow: Boolean,
    onAddonLeftOutDismiss: () -> Unit,
) {
    blockedAddonSong?.let { song ->
        AddonBlockedAlert(
            hazeState = hazeState,
            song = song,
            onDismiss = onBlockedAddonDismiss,
        )
    }

    addonLeftOutOfDownload?.let { (addon, why) ->
        InfoAlert(
            hazeState = hazeState,
            title = stringResource(R.string.download_addon_left_out_title, addon),
            description = when (why) {
                SourceRegistry.DownloadExclusion.DOWNLOADS_NOT_ALLOWED ->
                    stringResource(R.string.download_addon_not_allowed, addon)
                SourceRegistry.DownloadExclusion.NEEDS_LOSSLESS_OUTPUT ->
                    stringResource(R.string.download_addon_needs_lossless_output, addon)
            },
            onDismiss = onAddonLeftOutDismiss,
            inOwnWindow = inOwnWindow,
        )
    }
}
