package com.bxane.nuvx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.*
import kotlinx.coroutines.CancellationException

/** Space the floating mini player + tab bar cover, so the last items stay reachable. */
val BottomChromePadding = 170.dp

@Composable private fun TopBar(onAccount: () -> Unit, leading: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { leading() }
        GlassIconButton(NuvxIcons.Person, "Settings and about", onClick = onAccount)
    }

@Composable fun HomeScreen(history: History, router: BackendRouter, player: NuvxPlayer, onTogether: () -> Unit, onAccount: () -> Unit) {
    val played by history.played.collectAsState()
    val seed = played.firstOrNull()
    var picks by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(seed?.videoId) {
        picks = emptyList()
        if (seed != null) try { picks = router.radio(seed.videoId).tracks.filter { it.videoId != seed.videoId }.take(20) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* picks stay empty; the shelf hides */ }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item { TopBar(onAccount) { Image(NuvxMark, "nuvx", Modifier.size(44.dp)) } }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B4FD8), Color(0xFF6A2BD9), Color(0xFFC0367A)))).clickable(onClick = onTogether).padding(24.dp)) {
                Text("Listen together", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                Text("Start a room and hear the same song at the same moment as your friends.", color = Color.White.copy(0.85f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                Row(Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Together, null, Modifier.size(20.dp), tint = Color.Black)
                    Text("  Start a room", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
        if (played.isEmpty()) item {
            Text("Play something and it'll show up here, along with picks based on it.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
        } else {
            item { SectionHeader("Jump back in") }
            item { Shelf(played.take(15)) { player.playQueue(played, it) } }
        }
        if (picks.isNotEmpty()) {
            item { SectionHeader("Picked for you") }
            item { Text("Because you played ${seed?.title}", color = NuvxColors.Secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp, top = 0.dp)) }
            item { Shelf(picks, size = 200.dp) { player.playQueue(picks, it) } }
        }
    }
}

@Composable private fun Shelf(tracks: List<Track>, size: Dp = 160.dp, onPlay: (Int) -> Unit) =
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { i, t -> "$i-${t.videoId}" }) { i, t ->
            Column(Modifier.width(size).clickable { onPlay(i) }) {
                ArtworkTile(t, Modifier.size(size).clip(RoundedCornerShape(18.dp)))
                Text(t.title, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

private data class Mood(val label: String, val query: String, val colour: Color)
private val Moods = listOf(
    Mood("Chill", "chill music", Color(0xFF5C7FD6)), Mood("Focus", "focus music instrumental", Color(0xFF6B6B70)),
    Mood("Energize", "upbeat energy songs", Color(0xFFD1A100)), Mood("Workout", "workout music", Color(0xFFE0562B)),
    Mood("Feel good", "feel good songs", Color(0xFF3FA65A)), Mood("Party", "party hits", Color(0xFF9B5BE0)),
    Mood("Romance", "love songs", Color(0xFFC0263A)), Mood("Sad", "sad songs", Color(0xFF4C5D73)),
    Mood("Sleep", "sleep music", Color(0xFF253A6B)), Mood("Commute", "road trip songs", Color(0xFFB0782A)),
)

@Composable fun ExploreScreen(onMood: (String) -> Unit, onAccount: () -> Unit) =
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomChromePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column { TopBar(onAccount); LargeTitle("Explore", Modifier.padding(bottom = 8.dp)) } }
        items(Moods) { m ->
            Box(Modifier.aspectRatio(1.6f).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(m.colour, m.colour.copy(alpha = 0.55f)))).clickable { onMood(m.query) }.padding(14.dp)) {
                Text(m.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

@Composable fun LibraryScreen(history: History, state: PlayerState, player: NuvxPlayer, onQueue: () -> Unit, onTogether: () -> Unit, onAccount: () -> Unit) {
    val played by history.played.collectAsState()
    LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item { TopBar(onAccount) }
        item { LargeTitle("Library", Modifier.padding(bottom = 12.dp)) }
        item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A2C33), Color(0xFF16171B)))).padding(20.dp)) {
                Text("YOUR LISTENING", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                Text("${played.size}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text(if (played.size == 1) "SONG PLAYED ON THIS DEVICE" else "SONGS PLAYED ON THIS DEVICE", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp)
            }
        }
        item { NavRow(NuvxIcons.Queue, "Queue", if (state.queue.isEmpty()) "Empty" else "${state.queue.size} songs", onQueue) }
        item { NavRow(NuvxIcons.Together, "Listen Together", "Rooms with friends", onTogether) }
        item { NavRow(NuvxIcons.Settings, "Settings & About", null, onAccount) }
        if (played.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            itemsIndexed(played) { i, t -> TrackRow(t, active = state.current?.videoId == t.videoId) { player.playQueue(played, i) } }
        }
    }
}

@Composable fun TrackRow(track: Track, active: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkTile(track, Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = if (active) NuvxColors.Accent else NuvxColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistLine, color = NuvxColors.Secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }

@Composable fun SearchScreen(router: BackendRouter, player: NuvxPlayer, history: History, initialQuery: String?, onAccount: () -> Unit) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery.orEmpty()) }
    var submitted by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents by history.searches.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialQuery == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(submitted) {
        val q = submitted ?: return@LaunchedEffect
        busy = true; error = null; results = emptyList()
        try { results = router.search(q).tracks; history.searched(q); if (results.isEmpty()) error = "No songs found for \"$q\"." }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Search isn't available right now. Check your connection, or try the other engine in Settings." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).height(48.dp).glass().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NuvxIcons.Search, null, Modifier.size(20.dp), tint = NuvxColors.Secondary)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Artists, songs and more", color = NuvxColors.Tertiary, fontSize = 17.sp)
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" }, singleLine = true,
                        textStyle = TextStyle(color = NuvxColors.Text, fontSize = 17.sp), cursorBrush = SolidColor(NuvxColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitted = query.trim() }))
                }
                if (query.isNotEmpty()) PlainIconButton(NuvxIcons.Close, "Clear search", 32.dp) { query = ""; submitted = null; results = emptyList(); error = null }
            }
            GlassIconButton(NuvxIcons.Person, "Settings and about", onClick = onAccount)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f))
        LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            if (submitted == null) {
                if (recents.isNotEmpty()) {
                    item { SectionHeader("Recent searches") { Text("Clear", color = NuvxColors.Text, fontSize = 16.sp, modifier = Modifier.clickable { history.clearSearches() }) } }
                    items(recents) { q ->
                        Row(Modifier.fillMaxWidth().clickable { query = q; submitted = q }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NuvxIcons.Search, null, Modifier.size(18.dp), tint = NuvxColors.Secondary)
                            Text(q, color = NuvxColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PlainIconButton(NuvxIcons.Close, "Remove $q from recent searches", 32.dp) { history.forgetSearch(q) }
                        }
                    }
                } else item { Text("Find songs, artists and moods.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) }
            }
            error?.let { item { Text(it, color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) } }
            itemsIndexed(results) { i, t -> TrackRow(t) { player.playQueue(results, i) } }
        }
    }
}
