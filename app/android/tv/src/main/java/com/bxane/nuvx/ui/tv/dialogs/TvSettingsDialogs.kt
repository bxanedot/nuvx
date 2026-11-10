package com.bxane.nuvx.ui.tv.dialogs

import com.bxane.nuvx.ui.icons.SfIcons
import android.app.Activity
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.bxane.nuvx.BuildConfig
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.TvSettings
import com.bxane.nuvx.ui.tv.TvAppMark
import com.bxane.nuvx.ui.tv.components.TvDialog
import com.bxane.nuvx.ui.tv.components.TvDialogButton
import com.bxane.nuvx.ui.tv.components.TvListRow
import com.bxane.nuvx.ui.tv.components.TvTextField
import com.bxane.nuvx.ui.tv.components.tvInitialFocus
import com.bxane.nuvx.ui.tv.display.TvRefreshRateController
import com.bxane.nuvx.ui.tv.display.TvRefreshRatePreference
import com.bxane.nuvx.ui.tv.personalization.AppThemeOption
import com.bxane.nuvx.ui.tv.theme.TvGlass
import com.bxane.nuvx.ui.tv.theme.TvType

@Composable
fun TvDiscordDialog(onDismiss: () -> Unit) {
    val enabled by AppSettings.discordRpcEnabled.collectAsState()
    val savedToken by AppSettings.discordToken.collectAsState()
    var token by remember { mutableStateOf(savedToken) }

    TvDialog(
        title = "Discord",
        message = "Show what you're listening to in your Discord status.",
        onDismissRequest = onDismiss,
    ) {
        TvTextField(
            value = token,
            onValueChange = { token = it },
            placeholder = "Discord Token",
            modifier = Modifier.tvInitialFocus(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        TvDialogButton(
            text = if (enabled) "Turn Off" else "Turn On",
            enabled = enabled || token.isNotBlank(),
            onClick = {
                AppSettings.setDiscordToken(token)
                AppSettings.setDiscordRpcEnabled(!enabled)
                onDismiss()
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = "Cancel", onClick = onDismiss)
    }
}

@Composable
fun TvScrobbleDialog(onDismiss: () -> Unit) {
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsState()
    val lastfmUser by AppSettings.lastfmUsername.collectAsState()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsState()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsState()

    TvDialog(
        title = "Scrobbling",
        message = "Connect these on the nuvx phone app; the TV uses the same accounts.",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TvListRow(
                title = "Last.fm",
                value = if (lastfmEnabled && lastfmUser.isNotBlank()) lastfmUser else "Not Connected",
                enabled = false,
                onClick = {},
            )
            TvListRow(
                title = "ListenBrainz",
                value = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) "Connected" else "Not Connected",
                enabled = false,
                onClick = {},
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        TvDialogButton(text = "OK", onClick = onDismiss, initialFocus = true)
    }
}

@Composable
fun TvAboutDialog(onDismiss: () -> Unit) {
    TvDialog(title = "nuvx", message = "Version ${BuildConfig.VERSION_NAME}", onDismissRequest = onDismiss) {
        TvAppMark(size = 77.dp, modifier = Modifier.padding(bottom = 21.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TvListRow(title = "bxane", value = "Creator", enabled = false, onClick = {})
            TvListRow(title = "Nithyanantha", value = "TV App", enabled = false, onClick = {})
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Free software under the GNU GPL v3. Not affiliated with YouTube or Google.",
            style = TvType.Caption,
            color = TvGlass.TextTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        TvDialogButton(text = "OK", onClick = onDismiss, initialFocus = true)
    }
}

@Composable
fun TvRefreshRateDialog(onDismiss: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val capabilities by TvRefreshRateController.capabilities.collectAsState()
    val current by TvRefreshRateController.preference.collectAsState()

    TvDialog(
        title = "Refresh Rate",
        message = "Now ${capabilities.currentPhysicalWidth} × ${capabilities.currentPhysicalHeight} at " +
            String.format(java.util.Locale.US, "%.0f Hz", capabilities.actualRefreshRateHz),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TvRefreshRatePreference.entries.forEach { preference ->
                val supported = preference != TvRefreshRatePreference.SMOOTH_60 || capabilities.is60HzSupported
                TvListRow(
                    title = preference.label,
                    subtitle = preference.description,
                    trailingIcon = if (preference == current) SfIcons.Check else null,
                    enabled = supported,
                    modifier = if (preference == current) Modifier.tvInitialFocus() else Modifier,
                    onClick = {
                        activity?.let { TvRefreshRateController.setPreference(it, preference) }
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
fun TvThemeDialog(onDismiss: () -> Unit) {
    val currentId by TvSettings.tvTheme.collectAsState()
    val current = AppThemeOption.fromId(currentId)

    TvDialog(title = "Appearance", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            AppThemeOption.entries.forEach { theme ->
                TvListRow(
                    title = theme.title,
                    subtitle = theme.description,
                    trailingIcon = if (theme == current) SfIcons.Check else null,
                    modifier = if (theme == current) Modifier.tvInitialFocus() else Modifier,
                    onClick = {
                        TvSettings.setTvTheme(theme.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
fun TvLyricsSourcesDialog(onDismiss: () -> Unit) {
    val selected by AppSettings.lyricsSources.collectAsState()
    val savedOrder by AppSettings.lyricsSourceOrder.collectAsState()
    val wordFirst by AppSettings.prioritizeSyllableSync.collectAsState()
    val order = remember(savedOrder) { LyricsSource.ordered(savedOrder) }

    TvDialog(
        title = "Lyrics Sources",
        message = "Select to turn a source on or off. Press Left or Right to move it up or down; the first is asked first.",
        width = 512.dp,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 304.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(order, key = { _, source -> source.name }) { index, source ->
                val checked = source in selected
                TvListRow(
                    title = source.label,
                    subtitle = source.detail,
                    value = "${index + 1}",
                    leadingIcon = if (checked) SfIcons.Check else null,
                    modifier = Modifier
                        .tvInitialFocus(enabled = index == 0)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val target = when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> index - 1
                                KeyEvent.KEYCODE_DPAD_RIGHT -> index + 1
                                else -> return@onKeyEvent false
                            }
                            if (target in order.indices) {
                                AppSettings.setLyricsSourceOrder(
                                    order.toMutableList().apply { add(target, removeAt(index)) },
                                )
                            }
                            true
                        },
                    onClick = {
                        if (checked && selected.size <= 1) return@TvListRow
                        AppSettings.setLyricsSources(if (checked) selected - source else selected + source)
                    },
                )
            }
            item(key = "wordFirst") {
                TvListRow(
                    title = "Prefer Word-Synced",
                    subtitle = "Hold out for word-by-word lyrics before settling for line-by-line.",
                    value = if (wordFirst) "On" else "Off",
                    onClick = { AppSettings.setPrioritizeSyllableSync(!wordFirst) },
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        TvDialogButton(text = "Reset to Default", onClick = AppSettings::resetLyricsSourceSettings)
        Spacer(modifier = Modifier.height(6.dp))
        TvDialogButton(text = "Done", onClick = onDismiss)
    }
}
