package com.bxane.nuvx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.core.*
import kotlinx.coroutines.CancellationException

private val Blue = Color(0xFF67ACFF)
private val Background = Color(0xFF0C1019)
private enum class Screen(val label: String) { SEARCH("Search"), QUEUE("Queue"), PLAYER("Playing"), SETTINGS("Settings") }

@Composable
fun NuvxApp(router: BackendRouter, store: KeyValueStore, player: NuvxPlayer, lyrics: LrcLib, watch: Boolean) {
    val state by player.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.SEARCH) }
    var welcomed by remember { mutableStateOf(store.get("welcomeSeen") == "true") }
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Background, surface = Color(0xFF151C29), onSurface = Color(0xFFEAF0FA))) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            if (watch) {
                WatchApp(router, store, player)
            } else if (!welcomed) {
                Column(Modifier.safeDrawingPadding().fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Brand(96)
                    Spacer(Modifier.height(24.dp))
                    Text("Your next favourite.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Music, with room to listen.", color = Blue, modifier = Modifier.padding(vertical = 16.dp))
                    Text("Search YouTube Music and play available tracks as a guest. No Google account is connected. Streaming availability depends on the service and your region.")
                    Button(onClick = { store.put("welcomeSeen", "true"); welcomed = true }, modifier = Modifier.padding(top = 32.dp)) { Text("Start listening") }
                }
            } else BoxWithConstraints(Modifier.safeDrawingPadding().fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) Column(Modifier.width(200.dp).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Brand(56)
                        Text("nuvx", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Screen.entries.forEach { item ->
                            TextButton(onClick = { screen = item }, modifier = Modifier.fillMaxWidth()) { Text(if (screen == item) "• ${item.label}" else item.label) }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!wide) Brand(40)
                            Text(screen.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = if (wide) 0.dp else 12.dp))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (screen) {
                                Screen.SEARCH -> Search(router, player)
                                Screen.QUEUE -> TrackList(state.queue, "Your queue is empty. Find something in Search.", onPlay = player::skipTo, onQueue = { player.enqueue(listOf(it)) })
                                Screen.PLAYER -> NowPlaying(player, state, lyrics)
                                Screen.SETTINGS -> Settings(router, store)
                            }
                        }
                        if (state.current != null && screen != Screen.PLAYER) MiniPlayer(state, player) { screen = Screen.PLAYER }
                        if (!wide) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp)) {
                            Screen.entries.forEach { item ->
                                TextButton(onClick = { screen = item }, modifier = Modifier.weight(1f)) {
                                    Text(item.label, color = if (item == screen) Blue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Brand(size: Int) {
    Image(NuvxMark, "Nuvx — bxane's lightning mark", Modifier.size(size.dp), contentScale = ContentScale.Fit)
}

@Composable private fun Search(router: BackendRouter, player: NuvxPlayer) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (submitted.isBlank()) return@LaunchedEffect
        busy = true; error = null; tracks = emptyList()
        try { tracks = router.search(submitted).tracks; searched = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Search is unavailable. Check your connection or try the other engine in Settings." }
        finally { busy = false }
    }
    val submit = { if (query.isNotBlank()) { submitted = query.trim(); request++ }; Unit }
    Column {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Songs, artists, moods") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            Button(onClick = submit, enabled = query.isNotBlank()) { Text("Find") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        if (error != null) Text(error!!, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
        TrackList(tracks, if (searched) "No playable tracks found. Try another search." else "Find your frequency.\nSearch for a song or an artist to begin.",
            onPlay = { player.playQueue(tracks, it) }, onQueue = { player.enqueue(listOf(it)) })
    }
}

@Composable private fun TrackList(tracks: List<Track>, empty: String, onPlay: (Int) -> Unit, onQueue: (Track) -> Unit) {
    if (tracks.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Brand(80); Spacer(Modifier.height(20.dp)); Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(tracks) { index, track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPlay(index) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(56.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(track.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onQueue(track) }) { Text("+ Queue") }
            }
        }
    }
}

@Composable private fun Artwork(track: Track?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF173D63), Color(0xFF3D2464)))), contentAlignment = Alignment.Center) {
        if (track?.artworkUrl != null) AsyncImage(track.artworkUrl, "Artwork for ${track.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Brand(40)
    }
}

@Composable private fun MiniPlayer(state: PlayerState, player: NuvxPlayer, open: () -> Unit) {
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(state.current, Modifier.size(48.dp).clickable(onClick = open))
            Column(Modifier.weight(1f).clickable(onClick = open).padding(horizontal = 12.dp)) {
                Text(state.current?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.buffering) "Loading audio…" else state.current?.artistLine.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = player::togglePlay) { Text(if (state.isPlaying) "Pause" else "Play") }
        }
    }
}

@Composable private fun NowPlaying(player: NuvxPlayer, state: PlayerState, library: LrcLib) {
    val track = state.current
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsStatus by remember { mutableStateOf("Lyrics unavailable") }
    LaunchedEffect(track?.videoId) {
        lyrics = null
        if (track != null) {
            lyricsStatus = "Finding lyrics…"
            try { lyrics = library.find(track); lyricsStatus = "No lyrics found for this track" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { lyricsStatus = "Lyrics could not be loaded" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(track, Modifier.sizeIn(maxWidth = 340.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(24.dp))
        Text(track?.title ?: "Nothing playing yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(track?.artistLine.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = state.positionMs.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { player.seekTo(it.toLong()) },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(state.positionMs)); Text(time(state.durationMs)) }
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = player::previous, enabled = track != null) { Text("Previous") }
            Button(onClick = player::togglePlay, enabled = track != null) { Text(if (state.isPlaying) "Pause" else "Play") }
            OutlinedButton(onClick = player::next, enabled = state.index < state.queue.lastIndex) { Text("Next") }
        }
        if (state.buffering) CircularProgressIndicator(Modifier.size(24.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        when (val text = lyrics) {
            is Lyrics.Plain -> Text(text.text, style = MaterialTheme.typography.bodyLarge)
            is Lyrics.Synced -> {
                val active = text.lineAt(state.positionMs)
                text.lines.forEachIndexed { index, line ->
                    Text(line.text, color = if (index == active) Blue else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
            Lyrics.Instrumental -> Text("Instrumental")
            null -> Text(lyricsStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun time(ms: Long): String = "${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

@Composable private fun Settings(router: BackendRouter, store: KeyValueStore) {
    val selected by router.selected.collectAsState()
    val fallback by router.fallback.collectAsState()
    val health by router.health.collectAsState()
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Streaming engine", style = MaterialTheme.typography.titleLarge)
        router.available.forEach { backend ->
            Row(Modifier.fillMaxWidth().clickable { router.choose(backend, store) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == backend, onClick = { router.choose(backend, store) })
                Column {
                    Text(backend.label + if (backend == BackendId.INNERTUBEX) " · Default" else " · Alternative")
                    Text(health[backend]?.let { "${it.operation}: ${if (it.ok) "available" else "unavailable"} · ${it.latencyMs} ms" } ?: "Not checked this session", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Automatic fallback"); Text("Try the other engine when a request fails.", style = MaterialTheme.typography.bodySmall) }
            Switch(fallback, onCheckedChange = { router.chooseFallback(it, store) })
        }
        Text("The selected engine applies to new requests. eXcor streams may be refused by the service; fallback can use InnerTubeX.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Brand(64)
        Text("Nuvx", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Developed and led by bxane. Brand icons supplied by bxane.")
        listOf("Official Nuvx website" to "https://nuvx.lol", "Developer portfolio" to "https://bxane.lol",
            "Alternative developer portfolio" to "https://guns.lol/bxane", "Community Discord" to "https://discord.gg/h4Wsu824Uf",
            "Contact bxane" to "mailto:bxanee@gmail.com", "Source code and licences" to "https://github.com/bxanedot/nuvx").forEach { (label, url) ->
            TextButton(onClick = { uri.openUri(url) }) { Text(label) }
        }
        Text("Early development · GPL-3.0-only\nInnerTubeX · GPL-3.0\neXcor · Apache-2.0\nNo Google account connected.", style = MaterialTheme.typography.bodySmall)
    }
}
