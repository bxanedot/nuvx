package com.licht.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licht.nuvx.R
import com.licht.nuvx.data.sync.NuvxSync
import com.licht.nuvx.playback.cast.ComputerSpeaker
import com.licht.nuvx.ui.icons.SfIcons

/**
 * Settings → Sync & Connect, the Connect half. Playback itself moves through the player's speaker button, like a
 * watch or a Cast speaker; this only says where the computer is and how to use it.
 */
@Composable
internal fun ConnectSettingsGroup(search: SettingsSearch) {
    val status by NuvxSync.status.collectAsStateWithLifecycle()
    val computer by ComputerSpeaker.state.collectAsStateWithLifecycle()
    val paired = status.enabled && NuvxSync.isLan
    LaunchedEffect(paired) { if (paired) ComputerSpeaker.current?.refresh() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    val copyingText = stringResource(R.string.nuvx_files_copying)
    val doneText = stringResource(R.string.nuvx_files_done)
    val nothingText = stringResource(R.string.nuvx_files_nothing)
    val unreachableText = stringResource(R.string.nuvx_sync_lan_unreachable)
    fun copyAll() = scope.launch {
        copying = true
        copyStatus = null
        try {
            val files = withContext(Dispatchers.IO) { com.licht.nuvx.data.sync.NuvxFiles.list() }.getOrElse {
                copyStatus = unreachableText
                return@launch
            }.filterNot { com.licht.nuvx.download.Downloads.saved.value.containsKey(it.id) }
            if (files.isEmpty()) {
                copyStatus = nothingText
                return@launch
            }
            var copied = 0
            for ((i, file) in files.withIndex()) {
                copyStatus = copyingText.format(i + 1, files.size)
                val result = withContext(Dispatchers.IO) {
                    runCatching { com.licht.nuvx.download.Downloads.copyFromComputer(context, file) }
                }
                if (result.isFailure) {
                    // Storage full or over the limit: stop instead of failing every remaining song the same way.
                    val why = result.exceptionOrNull()?.message ?: unreachableText
                    copyStatus = if (copied > 0) doneText.format(copied) + " · " + why else why
                    return@launch
                }
                copied++
            }
            copyStatus = doneText.format(copied)
        } finally {
            copying = false
        }
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.nuvx_connect), footer = stringResource(R.string.nuvx_connect_footer)) {
        val title = stringResource(R.string.nuvx_connect)
        row(title, "remote", "control", "computer", "speaker", "connect", "handoff") {
            if (!paired) {
                SettingsRow(
                    icon = SfIcons.Tv,
                    title = stringResource(R.string.nuvx_connect_pair_first),
                    subtitle = stringResource(R.string.nuvx_connect_pair_first_subtitle),
                )
            } else {
                val name = computer.name ?: stringResource(R.string.computer_output_name)
                SettingsRow(
                    icon = SfIcons.Tv,
                    title = name,
                    subtitle = when {
                        computer.active -> stringResource(R.string.nuvx_connect_handed_off)
                        computer.available -> stringResource(R.string.nuvx_connect_how, name)
                        else -> stringResource(R.string.nuvx_connect_not_reachable)
                    },
                    onClick = { ComputerSpeaker.current?.refresh() },
                )
            }
        }
        if (paired) {
            val name = computer.name ?: stringResource(R.string.computer_output_name)
            val copyTitle = stringResource(R.string.nuvx_files_copy_title, name)
            row(copyTitle, "download", "copy", "offline", "files", "songs", "transfer") {
                SettingsRow(
                    icon = SfIcons.Download,
                    title = copyTitle,
                    subtitle = copyStatus ?: stringResource(R.string.nuvx_files_copy_subtitle),
                    enabled = !copying,
                    onClick = { copyAll() },
                )
            }
        }
    }
}
