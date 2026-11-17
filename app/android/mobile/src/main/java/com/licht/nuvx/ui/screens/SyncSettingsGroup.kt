package com.licht.nuvx.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licht.nuvx.R
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import com.licht.nuvx.data.sync.NuvxSync
import com.licht.nuvx.data.sync.SyncDevice
import com.licht.nuvx.data.sync.SyncResult
import com.licht.nuvx.ui.components.QrCode
import com.licht.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SyncDialog {
    data object Setup : SyncDialog
    data class Pairing(val server: String, val code: String, val secret: String, val expiresAtMs: Long) : SyncDialog
    data object Approve : SyncDialog
    data class Devices(val devices: List<SyncDevice>, val you: String) : SyncDialog
    data object Leave : SyncDialog
}

private fun SyncResult.problem(): String? = when (this) {
    is SyncResult.Stop -> why
    is SyncResult.RetryLater -> why
    else -> null
}

@Composable
internal fun SyncSettingsGroup(search: SettingsSearch) {
    val status by NuvxSync.status.collectAsStateWithLifecycle()
    val playlists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SyncDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun io(block: suspend () -> Unit) = scope.launch { busy = true; message = null; try { block() } finally { busy = false } }

    SearchableSettingsGroup(search, header = stringResource(R.string.nuvx_sync), footer = stringResource(R.string.nuvx_sync_footer)) {
        val title = stringResource(R.string.nuvx_sync)
        row(title, "sync", "devices", "pair", "qr", "cloud", "backup") {
            SettingsRow(
                icon = if (status.enabled) SfIcons.CloudDone else SfIcons.CloudOff,
                title = title,
                subtitle = when {
                    !status.enabled -> stringResource(R.string.off)
                    status.syncing -> stringResource(R.string.nuvx_sync_syncing)
                    status.error != null -> status.error
                    status.lastSyncMs > 0 -> stringResource(R.string.nuvx_sync_last, DateUtils.getRelativeTimeSpanString(status.lastSyncMs).toString())
                    else -> stringResource(R.string.nuvx_sync_syncing)
                },
                onClick = { if (status.enabled) NuvxSync.requestSync() else dialog = SyncDialog.Setup },
            )
        }
        if (status.enabled) {
            val approveTitle = stringResource(R.string.nuvx_sync_add_device)
            row(approveTitle, "pair", "qr", "code", "device") {
                SettingsRow(icon = SfIcons.Add, title = approveTitle, onClick = { dialog = SyncDialog.Approve })
            }
            val devicesTitle = stringResource(R.string.nuvx_sync_devices)
            row(devicesTitle, "devices", "revoke", "remove") {
                SettingsRow(icon = SfIcons.PhoneIphone, title = devicesTitle, enabled = !busy, onClick = {
                    io {
                        when (val r = withContext(Dispatchers.IO) { NuvxSync.devices() }) {
                            is SyncResult.Devices -> dialog = SyncDialog.Devices(r.devices, r.you)
                            else -> message = r.problem()
                        }
                    }
                })
            }
            val leaveTitle = stringResource(R.string.nuvx_sync_turn_off)
            row(leaveTitle, "off", "leave", "delete") {
                SettingsRow(icon = SfIcons.Trash, title = leaveTitle, onClick = { dialog = SyncDialog.Leave })
            }
        }
        message?.let { text -> row(text) { SettingsRow(title = text) } }
    }

    when (val d = dialog) {
        null -> Unit
        SyncDialog.Setup -> {
            var server by remember { mutableStateOf(status.server) }
            val valid = NuvxSync.normalizeServer(server)
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = null },
                title = { Text(stringResource(R.string.nuvx_sync)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text(stringResource(R.string.nuvx_sync_server)) },
                            placeholder = { Text("https://sync.example.org") },
                            singleLine = true,
                            isError = server.isNotBlank() && valid == null,
                            supportingText = { if (server.isNotBlank() && valid == null) Text(stringResource(R.string.nuvx_sync_server_https)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Consent: say exactly what leaves the device before anything does.
                        Text(stringResource(R.string.nuvx_sync_consent, playlists.size), style = MaterialTheme.typography.bodyMedium)
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = valid != null && !busy, onClick = {
                        io {
                            when (val r = withContext(Dispatchers.IO) { NuvxSync.startPairing(valid!!) }) {
                                is SyncResult.PairingStarted -> dialog = SyncDialog.Pairing(valid!!, r.code, r.secret, r.expiresAtMs)
                                else -> message = r.problem()
                            }
                        }
                    }) { Text(stringResource(R.string.nuvx_sync_join)) }
                },
                dismissButton = {
                    TextButton(enabled = valid != null && !busy, onClick = {
                        io {
                            when (val r = withContext(Dispatchers.IO) { NuvxSync.createSpace(valid!!) }) {
                                is SyncResult.Joined -> dialog = null
                                else -> message = r.problem()
                            }
                        }
                    }) { Text(stringResource(R.string.nuvx_sync_start_new)) }
                },
            )
        }
        is SyncDialog.Pairing -> {
            var expired by remember(d) { mutableStateOf(false) }
            LaunchedEffect(d) {
                while (System.currentTimeMillis() < d.expiresAtMs) {
                    when (val r = withContext(Dispatchers.IO) { NuvxSync.claim(d.server, d.code, d.secret) }) {
                        is SyncResult.Joined -> { dialog = null; return@LaunchedEffect }
                        is SyncResult.Waiting, is SyncResult.RetryLater -> delay(2_000)
                        else -> { message = r.problem(); expired = true; return@LaunchedEffect }
                    }
                }
                expired = true
            }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.nuvx_sync_join)) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        if (expired) {
                            Text(message ?: stringResource(R.string.nuvx_sync_code_expired))
                        } else {
                            Text(stringResource(R.string.nuvx_sync_join_steps))
                            QrCode(content = "nuvxsync:${d.code}")
                            Text(d.code.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 28.sp)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        SyncDialog.Approve -> {
            var code by remember { mutableStateOf("") }
            var approved by remember { mutableStateOf<String?>(null) }
            val clean = code.uppercase().filter { it.isLetterOrDigit() }
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = null },
                title = { Text(stringResource(R.string.nuvx_sync_add_device)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (approved != null) {
                            // The new device's name is only known once approved: show it so a wrong one can be removed.
                            Text(stringResource(R.string.nuvx_sync_added, approved!!))
                        } else {
                            Text(stringResource(R.string.nuvx_sync_add_device_steps))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.take(12) },
                                label = { Text(stringResource(R.string.nuvx_sync_code)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    if (approved == null) {
                        TextButton(enabled = clean.length == 8 && !busy, onClick = {
                            io {
                                when (val r = withContext(Dispatchers.IO) { NuvxSync.approve(clean) }) {
                                    is SyncResult.Approved -> approved = r.deviceName
                                    else -> message = r.problem()
                                }
                            }
                        }) { Text(stringResource(R.string.nuvx_sync_approve)) }
                    } else {
                        TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) }
                    }
                },
                dismissButton = { if (approved == null) TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        is SyncDialog.Devices -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.nuvx_sync_devices)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.devices.forEach { device ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name + if (device.id == d.you) " · " + stringResource(R.string.nuvx_sync_this_device) else "")
                                Text(
                                    stringResource(R.string.nuvx_sync_last, DateUtils.getRelativeTimeSpanString(device.seenMs).toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (device.id != d.you) {
                                TextButton(enabled = !busy, onClick = {
                                    io {
                                        when (val r = withContext(Dispatchers.IO) { NuvxSync.revoke(device.id) }) {
                                            SyncResult.Done -> dialog = d.copy(devices = d.devices - device)
                                            else -> message = r.problem()
                                        }
                                    }
                                }) { Text(stringResource(R.string.remove)) }
                            }
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) } },
        )
        SyncDialog.Leave -> {
            var deleteServer by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = null },
                title = { Text(stringResource(R.string.nuvx_sync_turn_off)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.nuvx_sync_turn_off_body))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteServer, onCheckedChange = { deleteServer = it })
                            Text(stringResource(R.string.nuvx_sync_delete_server))
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        io {
                            when (val r = withContext(Dispatchers.IO) { NuvxSync.leave(deleteServer) }) {
                                SyncResult.Done -> dialog = null
                                else -> message = r.problem()
                            }
                        }
                    }) { Text(stringResource(R.string.nuvx_sync_turn_off), color = if (deleteServer) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}
