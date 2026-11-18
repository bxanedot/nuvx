package com.licht.nuvx.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    }
}
