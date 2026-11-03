package com.bxane.nuvx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
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
        if (seed != null) try { picks = router.radio(seed.videoId).tracks.filter { it.videoId != seed.videoId }.take(30) }
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
            item { Shelf(picks.take(6), size = 200.dp) { player.playQueue(picks, it) } }
            if (picks.size > 6) {
                item { SectionHeader("Quick picks") }
                item { QuickPicks(picks.drop(6)) { i -> player.playQueue(picks, i + 6) } }
            }
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

/** Mood tiles: a colour band with real artwork (the mood's top song) fading in from the right; the colour alone if none loads. */
@Composable fun ExploreScreen(onMood: (String) -> Unit, router: BackendRouter? = null, onAccount: () -> Unit) {
    // shortcut: artwork is cached only while Explore is on screen; hoist to the app graph if re-fetching on return is noticeable.
    val art = remember { mutableStateMapOf<String, String?>() }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomChromePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column { TopBar(onAccount); LargeTitle("Explore", Modifier.padding(bottom = 8.dp)) } }
        items(Moods) { m ->
            if (router != null) LaunchedEffect(m.query) {
                if (m.query !in art) art[m.query] = try { router.search(m.query, SearchFilter.SONGS).tracks.mapNotNull { it.artworkUrl }.let { urls -> urls.firstOrNull { it !in art.values } ?: urls.firstOrNull() } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            Box(Modifier.aspectRatio(1.6f).clip(RoundedCornerShape(20.dp)).background(m.colour).focusRing(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Search ${m.label}") { onMood(m.query) }) {
                art[m.query]?.let { url ->
                    coil3.compose.AsyncImage(url, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(1f),
                        contentScale = ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.35f to m.colour, 0.75f to m.colour.copy(alpha = 0.15f))))
                }
                Text(m.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable fun LibraryScreen(history: History, state: PlayerState, player: NuvxPlayer, onQueue: () -> Unit, onLiked: () -> Unit, onTogether: () -> Unit, onAccount: () -> Unit, onLocalLibrary: (() -> Unit)? = null) {
    val played by history.played.collectAsState()
    val liked by history.liked.collectAsState()
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
        item { NavRow(NuvxIcons.HeartFilled, "Liked songs", if (liked.isEmpty()) "None yet" else songs(liked.size), onLiked) }
        item { NavRow(NuvxIcons.Queue, "Queue", if (state.queue.isEmpty()) "Empty" else songs(state.queue.size), onQueue) }
        if (onLocalLibrary != null) item { NavRow(NuvxIcons.Library, "On this device", "Music stored here", onLocalLibrary) }
        item { NavRow(NuvxIcons.Together, "Listen Together", "Rooms with friends", onTogether) }
        item { NavRow(NuvxIcons.Settings, "Settings and about", null, onAccount) }
        if (played.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            itemsIndexed(played) { i, t -> TrackRow(t, active = state.current?.videoId == t.videoId) { player.playQueue(played, i) } }
        }
    }
}

@Composable fun TrackRow(track: Track, modifier: Modifier = Modifier, active: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) =
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkTile(track, Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = if (active) NuvxColors.Accent else NuvxColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistLine, color = NuvxColors.Secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }

@Composable fun SearchScreen(router: BackendRouter, player: NuvxPlayer, history: History, initialQuery: String?, onOpen: (Collection) -> Unit, onAccount: () -> Unit) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery.orEmpty()) }
    var submitted by remember(initialQuery) { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var tops by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents by history.searches.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialQuery == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(submitted, filter) {
        val q = submitted ?: return@LaunchedEffect
        busy = true; error = null; results = emptyList(); albums = emptyList(); tops = emptyList()
        try {
            val found = router.search(q, filter)
            when (filter) {
                SearchFilter.ALBUMS -> albums = found.collections.filter { it.kind == Collection.Kind.ALBUM }
                SearchFilter.PLAYLISTS -> albums = found.collections.filter { it.kind == Collection.Kind.PLAYLIST }
                SearchFilter.ARTISTS -> albums = found.collections.filter { it.kind == Collection.Kind.ARTIST }
                else -> { results = found.tracks; if (filter == SearchFilter.ALL) tops = found.collections.take(12) }
            }
            history.searched(q)
            if (results.isEmpty() && albums.isEmpty()) error = "Nothing found for \"$q\"."
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Search isn't available right now. Check your connection, or try the other engine in Settings." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).glass().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NuvxIcons.Search, null, Modifier.size(20.dp), tint = NuvxColors.Secondary)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) Text("Artists, songs and more", color = NuvxColors.Tertiary, fontSize = 17.sp)
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" }, singleLine = true,
                        textStyle = TextStyle(color = NuvxColors.Text, fontSize = 17.sp), cursorBrush = SolidColor(NuvxColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitted = query.trim() }))
                }
                if (query.isNotEmpty()) PlainIconButton(NuvxIcons.Close, "Clear search", 44.dp) { query = ""; submitted = null; results = emptyList(); error = null }
            }
            GlassIconButton(NuvxIcons.Person, "Settings and about", onClick = onAccount)
        }
        FilterChips(filter) { filter = it }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f))
        LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            if (submitted == null) {
                if (recents.isNotEmpty()) {
                    item { SectionHeader("Recent searches") { Text("Clear", color = NuvxColors.Text, fontSize = 16.sp, modifier = Modifier.clickable { history.clearSearches() }) } }
                    items(recents) { q ->
                        Row(Modifier.fillMaxWidth().clickable { query = q; submitted = q }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NuvxIcons.Search, null, Modifier.size(18.dp), tint = NuvxColors.Secondary)
                            Text(q, color = NuvxColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PlainIconButton(NuvxIcons.Close, "Remove $q from recent searches", 44.dp) { history.forgetSearch(q) }
                        }
                    }
                } else item { Text("Find songs, artists and moods.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) }
            }
            error?.let { item { Text(it, color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) } }
            if (tops.isNotEmpty()) {
                item { SectionHeader("Top results") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(tops, key = { it.browseId }) { c -> CollectionCard(c) { onOpen(c) } }
                    }
                }
                if (results.isNotEmpty()) item { SectionHeader("Songs") }
            }
            itemsIndexed(results) { i, t -> TrackRow(t, trailing = { TrackMenu(player, t) }) { player.playQueue(results, i) } }
            items(albums) { a -> AlbumRow(a) { onOpen(a) } }
        }
    }
}

/** Segmented glass control; the selected filter sits in a lighter inner pill. */
@Composable private fun FilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) =
    // Scrolls sideways so five filters still fit on narrow phones and at large font sizes.
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).fillMaxWidth().heightIn(min = 40.dp).height(IntrinsicSize.Min).glass(RoundedCornerShape(20.dp))
        .horizontalScroll(rememberScrollState()).padding(4.dp)) {
        SearchFilter.entries.forEach { f ->
            val active = f == selected
            Box(Modifier.widthIn(min = 72.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(if (active) Color.White.copy(0.16f) else Color.Transparent)
                .focusRing(RoundedCornerShape(16.dp)).clickable { onSelect(f) }.semantics { this.selected = active; role = Role.Tab }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(f.label, color = if (active) NuvxColors.Text else NuvxColors.Secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

/** Compact song rows, four deep, scrolling sideways in pages. */
@Composable private fun QuickPicks(tracks: List<Track>, onPlay: (Int) -> Unit) =
    LazyHorizontalGrid(GridCells.Fixed(4), Modifier.height(272.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)) {
        itemsIndexed(tracks, key = { i, t -> "$i-${t.videoId}" }) { i, t ->
            Row(Modifier.width(320.dp).height(68.dp).clip(RoundedCornerShape(12.dp)).clickable { onPlay(i) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkTile(t, Modifier.size(52.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, color = NuvxColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

@Composable private fun AlbumRow(album: Collection, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(album.artworkUrl, album.title, Modifier.size(64.dp).then(if (album.kind == Collection.Kind.ARTIST) Modifier.clip(CircleShape) else Modifier))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(album.title, color = NuvxColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            album.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp), tint = NuvxColors.Tertiary)
    }

@Composable private fun CoverArt(url: String?, title: String, modifier: Modifier) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(NuvxColors.Card)) {
        if (url != null) coil3.compose.AsyncImage(url, "Cover of $title", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }

/** Album page: big cover, title, artist and year, Play, then the numbered track list. */
@Composable fun AlbumPage(router: BackendRouter, player: NuvxPlayer, state: PlayerState, ref: Collection) {
    var album by remember(ref.browseId) { mutableStateOf<Album?>(null) }
    var failed by remember(ref.browseId) { mutableStateOf(false) }
    LaunchedEffect(ref.browseId) {
        try { album = router.album(ref.browseId) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val a = album
    LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(a?.artworkUrl ?: ref.artworkUrl, ref.title, Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f))
                Text(a?.title ?: ref.title, color = NuvxColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Text(listOfNotNull(a?.artist, a?.year).joinToString(" · ").ifEmpty { ref.subtitle.orEmpty() }, color = NuvxColors.Accent, fontSize = 17.sp)
                if (a != null && a.tracks.isNotEmpty()) Row(Modifier.padding(vertical = 18.dp).clip(CircleShape).background(Color.White).clickable { player.playQueue(a.tracks, 0) }
                    .semantics { role = Role.Button }.padding(horizontal = 32.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Play, null, Modifier.size(20.dp), tint = Color.Black)
                    Text("  Play", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        when {
            failed -> item { Text("This album could not be loaded. Check your connection and try again.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) }
            a == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f)) }
            else -> itemsIndexed(a.tracks) { i, t ->
                Row(Modifier.fillMaxWidth().clickable { player.playQueue(a.tracks, i) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.width(32.dp))
                    Text(t.title, color = if (state.current?.videoId == t.videoId) NuvxColors.Accent else NuvxColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    t.durationMs?.let { Text(clock(it), color = NuvxColors.Secondary, fontSize = 14.sp) }
                    TrackMenu(player, t)
                }
            }
        }
    }
}

internal fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

/** More menu on a song row: insert it after the current song, or add it to the end of the queue. */
@Composable fun TrackMenu(player: NuvxPlayer, track: Track) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlainIconButton(NuvxIcons.More, "More options for ${track.title}", 44.dp) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Color(0xFF232327)) {
            androidx.compose.material3.DropdownMenuItem({ Text("Play next") }, { open = false; player.playNext(listOf(track)) })
            androidx.compose.material3.DropdownMenuItem({ Text("Add to queue") }, { open = false; player.enqueue(listOf(track)) })
        }
    }
}
