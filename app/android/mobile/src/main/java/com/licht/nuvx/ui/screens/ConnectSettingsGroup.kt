package com.licht.nuvx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licht.nuvx.R
import com.licht.nuvx.data.sync.NuvxConnect
import com.licht.nuvx.data.sync.NuvxSync
import com.licht.nuvx.playback.rememberMediaController
import com.licht.nuvx.ui.icons.SfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Settings → Nuvx Connect: control nuvx on the paired computer, or move this phone's song there. */
@Composable
internal fun ConnectSettingsGroup(search: SettingsSearch) {
    val status by NuvxSync.status.collectAsStateWithLifecycle()
    val paired = status.enabled && NuvxSync.isLan
    val controller = rememberMediaController()
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<NuvxConnect.Remote?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val unreachable = stringResource(R.string.nuvx_sync_lan_unreachable)
    val turnedOff = stringResource(R.string.nuvx_connect_off_on_computer)
    fun problemOf(e: Throwable) = if (e.message == "HTTP 403") turnedOff else unreachable
    val handedOff = stringResource(R.string.nuvx_connect_handed_off)
    val nothingToHand = stringResource(R.string.nuvx_connect_nothing_to_hand)

    // Poll while this page is open: the computer's state changes on its own (songs end, someone uses it).
    LaunchedEffect(paired, refresh) {
        if (!paired) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { NuvxConnect.state() }
                .onSuccess { remote = it; problem = null }
                .onFailure { remote = null; problem = problemOf(it) }
            delay(POLL_MS)
        }
    }
    fun send(command: JsonObject, done: String? = null) = scope.launch {
        withContext(Dispatchers.IO) { NuvxConnect.send(command) }
            .onSuccess { problem = done; delay(SETTLE_MS); refresh++ }
            .onFailure { problem = problemOf(it) }
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.nuvx_connect), footer = stringResource(R.string.nuvx_connect_footer)) {
        val title = stringResource(R.string.nuvx_connect)
        if (!paired) {
            row(title, "remote", "control", "computer", "handoff", "connect") {
                SettingsRow(icon = SfIcons.Tv, title = stringResource(R.string.nuvx_connect_pair_first), subtitle = stringResource(R.string.nuvx_connect_pair_first_subtitle))
            }
            return@SearchableSettingsGroup
        }
        row(title, "remote", "now playing", "computer") {
            val r = remote
            SettingsRow(
                icon = SfIcons.Tv,
                title = r?.title ?: stringResource(R.string.nuvx_connect_nothing_playing),
                subtitle = if (r?.title == null) problem else {
                    listOfNotNull(r.artist?.takeIf(String::isNotBlank), stringResource(R.string.nuvx_connect_paused).takeIf { !r.playing }, problem)
                        .joinToString(" · ")
                },
            )
        }
        row(title, "remote", "play", "pause", "next", "previous", "skip") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = MaterialTheme.colorScheme.onBackground
                IconButton(onClick = { send(NuvxConnect.action("previous")) }, enabled = remote != null) {
                    Icon(SfIcons.FastRewind, stringResource(R.string.nuvx_connect_previous), tint = tint, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { send(NuvxConnect.action("toggle")) }, enabled = remote?.title != null) {
                    val playing = remote?.playing == true
                    Icon(if (playing) SfIcons.Pause else SfIcons.Play, stringResource(if (playing) R.string.pause else R.string.play), tint = tint, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { send(NuvxConnect.action("next")) }, enabled = remote != null) {
                    Icon(SfIcons.FastForward, stringResource(R.string.nuvx_connect_next), tint = tint, modifier = Modifier.size(26.dp))
                }
            }
        }
        val handOffTitle = stringResource(R.string.nuvx_connect_hand_off)
        row(handOffTitle, "handoff", "move", "transfer", "computer") {
            SettingsRow(
                icon = SfIcons.PlayCircle,
                title = handOffTitle,
                subtitle = stringResource(R.string.nuvx_connect_hand_off_subtitle),
                enabled = remote != null,
                onClick = {
                    val c = controller
                    val item = c?.currentMediaItem
                    val command = item?.let {
                        NuvxConnect.handOff(it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(), c.currentPosition)
                    }
                    if (command == null) {
                        problem = nothingToHand
                    } else {
                        c.pause()
                        send(command, handedOff)
                    }
                },
            )
        }
    }
}

private const val POLL_MS = 1_500L
private const val SETTLE_MS = 400L
