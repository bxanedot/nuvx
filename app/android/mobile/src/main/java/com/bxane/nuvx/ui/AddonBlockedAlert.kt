package com.bxane.nuvx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.ui.components.InfoAlert
import dev.chrisbanes.haze.HazeState

@Composable
fun AddonBlockedAlert(hazeState: HazeState, song: Song, onDismiss: () -> Unit) {
    val addon = SourceRegistry.parseTrackKey(song.videoId)
        ?.let { (configId, _) -> SourceRegistry.config(configId)?.displayName }
        ?: stringResource(R.string.search_source_addons)
    val metered = AppSettings.meteredConnection.value == true
    val chosen = if (metered) AppSettings.audioQualityCellular.value else AppSettings.audioQualityWifi.value
    val message = if (chosen == AudioQuality.LOSSLESS) {
        stringResource(R.string.addon_blocked_watch, addon)
    } else {
        stringResource(
            R.string.addon_blocked_quality,
            stringResource(if (metered) R.string.mobile_data else R.string.wifi),
            stringResource(chosen.labelRes),
            addon,
        )
    }
    InfoAlert(
        hazeState = hazeState,
        title = stringResource(R.string.addon_blocked_title),
        description = message,
        onDismiss = onDismiss,
    )
}

private val AudioQuality.labelRes: Int
    get() = when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    }
