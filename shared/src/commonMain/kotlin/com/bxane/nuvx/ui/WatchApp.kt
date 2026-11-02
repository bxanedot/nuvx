package com.bxane.nuvx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.core.*
import kotlinx.coroutines.CancellationException

/** Standalone watch layout: one vertical action column stays inside the round display's safe area. */
@Composable
internal fun WatchApp(router: BackendRouter, store: KeyValueStore, player: NuvxPlayer) {
    val state by player.state.collectAsState()
    val engine by router.selected.collectAsState()
    val fallback by router.fallback.collectAsState()
    var page by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search as a guest") }
    LaunchedEffect(request) {
        if (query.isBlank() || request == 0) return@LaunchedEffect
        busy = true
        try { tracks = router.search(query.trim()).tracks; message = if (tracks.isEmpty()) "No tracks found" else "${tracks.size} tracks" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Search failed. Try again."; tracks = emptyList() }
        finally { busy = false }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (page == "home") {
                Image(NuvxMark, "Nuvx", Modifier.size(36.dp))
                Text("Nuvx", style = MaterialTheme.typography.titleLarge)
            } else TextButton(onClick = { page = "home" }) { Text("Back") }
        }
        when (page) {
            "home" -> {
                item { Text(state.current?.title ?: "Your music, here", style = MaterialTheme.typography.titleMedium) }
                item { Text(state.current?.artistLine ?: "No account connected", style = MaterialTheme.typography.bodySmall) }
                if (state.current != null) {
                    item { Button(onClick = player::togglePlay, modifier = Modifier.fillMaxWidth()) { Text(if (state.isPlaying) "Pause" else "Play") } }
                    item { TextButton(onClick = player::previous) { Text("Previous track") } }
                    item { TextButton(onClick = player::next, enabled = state.index < state.queue.lastIndex) { Text("Next track") } }
                    if (state.buffering) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                }
                item { Button(onClick = { page = "search" }, modifier = Modifier.fillMaxWidth()) { Text("Search") } }
                item { OutlinedButton(onClick = { page = "queue" }, modifier = Modifier.fillMaxWidth()) { Text("Queue") } }
                item { OutlinedButton(onClick = { page = "engine" }, modifier = Modifier.fillMaxWidth()) { Text("Engine") } }
                item { Text("Developed by bxane", style = MaterialTheme.typography.bodySmall) }
            }
            "search" -> {
                item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search music") }, singleLine = true) }
                item { Button(onClick = { request++ }, enabled = query.isNotBlank() && !busy) { Text("Find tracks") } }
                item { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) else Text(message, style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(tracks) { index, track ->
                    TextButton(onClick = { player.playQueue(tracks, index); page = "home" }, modifier = Modifier.fillMaxWidth()) { Text("${track.title}\n${track.artistLine}") }
                }
            }
            "queue" -> {
                if (state.queue.isEmpty()) item { Text("Queue is empty") }
                itemsIndexed(state.queue) { index, track ->
                    TextButton(onClick = { player.skipTo(index); page = "home" }, modifier = Modifier.fillMaxWidth()) { Text(track.title) }
                }
            }
            "engine" -> {
                item { Text("Streaming engine", style = MaterialTheme.typography.titleMedium) }
                router.available.forEach { backend ->
                    item { OutlinedButton(onClick = { router.choose(backend, store) }, modifier = Modifier.fillMaxWidth()) { Text((if (engine == backend) "✓ " else "") + backend.label) } }
                }
                item { Text("Automatic fallback", style = MaterialTheme.typography.bodySmall) }
                item { Switch(fallback, { router.chooseFallback(it, store) }) }
            }
        }
    }
}
