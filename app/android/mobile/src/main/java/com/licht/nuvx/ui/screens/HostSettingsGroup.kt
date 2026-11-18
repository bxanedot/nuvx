package com.licht.nuvx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licht.nuvx.R
import com.licht.nuvx.data.sync.LanHost
import com.licht.nuvx.data.sync.NuvxSync
import com.licht.nuvx.ui.components.IosSwitch
import com.licht.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Sync & Connect: this phone as the host other phones pair with, like the desktop app. Approval here
 * and the same 6-digit code on both phones; paired phones sync playlists, copy downloads and can play through this
 * one when remote control is allowed.
 */
@Composable
internal fun HostSettingsGroup(search: SettingsSearch) {
    val host by LanHost.state.collectAsStateWithLifecycle()
    val allowRemote by LanHost.allowRemote.collectAsStateWithLifecycle()
    val status by NuvxSync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val hosting = host.running && NuvxSync.isHosting
    // One sync slot: a phone paired with a computer or a server first leaves that, from its own row above.
    val blocked = status.enabled && !NuvxSync.isHosting
    val failed = stringResource(R.string.failed)
    val context = androidx.compose.ui.platform.LocalContext.current

    SearchableSettingsGroup(search, header = stringResource(R.string.nuvx_host_header), footer = stringResource(R.string.nuvx_host_footer)) {
        val title = stringResource(R.string.nuvx_host_title)
        row(title, "phone", "pair", "host", "other phone", "share", "code") {
            SettingsRow(
                icon = SfIcons.PhoneIphone,
                title = title,
                subtitle = when {
                    blocked -> stringResource(R.string.nuvx_host_blocked)
                    hosting -> stringResource(R.string.nuvx_host_on, host.code, LanHost.wifiAddress() ?: "–")
                    else -> stringResource(R.string.nuvx_host_off)
                },
                enabled = !blocked && !busy,
                trailing = {
                    IosSwitch(checked = hosting, onCheckedChange = if (blocked || busy) null else { on ->
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { if (on) NuvxSync.startHosting() else { NuvxSync.stopHosting(); true } }
                            if (!ok) android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_SHORT).show()
                            busy = false
                        }
                    })
                },
            )
        }
        if (!hosting) return@SearchableSettingsGroup
        row(title, "code") {
            SettingsRow(
                title = stringResource(R.string.nuvx_host_code_title),
                subtitle = stringResource(R.string.nuvx_host_code_subtitle),
                trailing = { Text(host.code, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground) },
            )
        }
        host.pending.forEach { p ->
            row(title, "approve", p.name) {
                SettingsRow(
                    icon = SfIcons.PhoneIphone,
                    title = stringResource(R.string.nuvx_host_wants, p.name),
                    subtitle = stringResource(R.string.nuvx_host_compare, p.code.take(3) + " " + p.code.drop(3)),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { LanHost.deny(p.id) }) { Text(stringResource(R.string.nuvx_host_decline)) }
                            TextButton(onClick = { LanHost.approve(p.id) }) { Text(stringResource(R.string.nuvx_host_approve)) }
                        }
                    },
                )
            }
        }
        host.devices.forEach { d ->
            row(title, "devices", d.name) {
                SettingsRow(
                    icon = SfIcons.PhoneIphone,
                    title = d.name,
                    subtitle = stringResource(R.string.nuvx_host_paired),
                    trailing = { TextButton(onClick = { LanHost.revoke(d.id) }) { Text(stringResource(R.string.nuvx_host_remove)) } },
                )
            }
        }
        val remoteTitle = stringResource(R.string.nuvx_host_remote_title)
        row(remoteTitle, "remote", "control", "speaker", "connect") {
            SettingsRow(
                icon = SfIcons.Tv,
                title = remoteTitle,
                subtitle = stringResource(R.string.nuvx_host_remote_subtitle),
                trailing = { IosSwitch(checked = allowRemote, onCheckedChange = LanHost::setAllowRemote) },
                onClick = { LanHost.setAllowRemote(!allowRemote) },
            )
        }
    }
}
