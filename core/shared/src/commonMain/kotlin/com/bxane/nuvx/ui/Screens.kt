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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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

@Composable private fun TopBar(onAccount: () -> Unit, leading: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { leading() }
        GlassIconButton(NuvxIcons.Person, "Settings and about", onClick = onAccount)
    }

@Composable fun HomeScreen(
    history: History,
    router: BackendRouter,
    player: NuvxPlayer,
    onTogether: () -> Unit,
    onAccount: () -> Unit,
    onExplore: () -> Unit,
    onSearch: () -> Unit,
    onMood: (String) -> Unit,
    androidUi: Boolean = false,
) {
    val played by history.played.collectAsState()
    val desktop = LocalDesktopLayout.current
    val moodArt = remember { mutableStateMapOf<String, String?>() }
    val seed = played.firstOrNull { !it.isLocal }
    var picks by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(seed?.videoId) {
        picks = emptyList()
        if (seed != null) try { picks = router.radio(seed.videoId).tracks.filter { it.videoId != seed.videoId }.take(30) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* picks stay empty; the shelf hides */ }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        if (!desktop) item { TopBar(onAccount) { NuvxBrandMark("nuvx", Modifier.size(44.dp)) } }
        if (androidUi) item { LargeTitle("Listen Now", Modifier.padding(top = 4.dp, bottom = 8.dp)) }
        if (desktop || androidUi) item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1C)).focusRing(RoundedCornerShape(14.dp)).clickable(onClick = onTogether)
                .semantics { role = Role.Button; contentDescription = "Listen together" }
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listen together", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Start a room and listen in sync with friends.", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
                Row(Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Together, null, Modifier.size(18.dp), tint = Color.Black)
                    Text("  Start a room", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        } else item {
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("Pick a place to start", color = NuvxColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Browse moods or search for songs, artists and albums.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeDiscoveryAction("Explore", NuvxIcons.Explore, onExplore, Modifier.weight(1f))
                    HomeDiscoveryAction("Search", NuvxIcons.Search, onSearch, Modifier.weight(1f))
                }
            }
        } else {
            item { SectionHeader(if (androidUi) "Recently Played" else "Jump back in") }
            item { Shelf(played.take(15)) { player.playQueue(played, it) } }
        }
        if (picks.isNotEmpty()) {
            item { SectionHeader(if (androidUi) "Quick picks" else "Picked for you") }
            item { Text("Because you played ${seed?.title}", color = NuvxColors.Secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp, top = 0.dp)) }
            item { Shelf(picks.take(6), size = if (desktop) 160.dp else 200.dp) { player.playQueue(picks, it) } }
            if (picks.size > 6) {
                item { SectionHeader(if (androidUi) "All hits" else "Quick picks") }
                item { QuickPicks(picks.drop(6)) { i -> player.playQueue(picks, i + 6) } }
            }
        }
        item { SectionHeader("Explore by mood") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(HomeMoods, key = Mood::query) { mood ->
                    MoodTile(mood, moodArt, router, Modifier.width(if (desktop) 208.dp else 156.dp).height(if (desktop) 120.dp else 98.dp), desktop, onMood)
                }
            }
        }
    }
}

@Composable private fun HomeDiscoveryAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Row(modifier.clip(CircleShape).background(NuvxColors.Card).focusRing(CircleShape).clickable(onClick = onClick)
        .semantics { role = Role.Button; contentDescription = label }
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = NuvxColors.Text)
        Text(label, Modifier.padding(start = 8.dp), color = NuvxColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }

@Composable private fun Shelf(tracks: List<Track>, size: Dp = 160.dp, onPlay: (Int) -> Unit) =
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { i, t -> "$i-${t.videoId}" }) { i, t ->
            Column(Modifier.width(size).clickable { onPlay(i) }) {
                ArtworkTile(t, Modifier.size(size).clip(RoundedCornerShape(18.dp)), 544)
                Text(t.title, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

private data class Mood(val label: String, val query: String, val colour: Color)
private val Moods = listOf(
    Mood("Chill", "chill music", Color(0xFF5C7FD6)), Mood("Christmas", "christmas music", Color(0xFF187A48)),
    Mood("Commute", "road trip songs", Color(0xFFB0782A)), Mood("Energize", "upbeat energy songs", Color(0xFFD1A100)),
    Mood("Feel good", "feel good songs", Color(0xFF3FA65A)), Mood("Focus", "focus music instrumental", Color(0xFF6B6B70)),
    Mood("Gaming", "gaming music", Color(0xFF424247)), Mood("Party", "party hits", Color(0xFF9B5BE0)),
    Mood("Romance", "love songs", Color(0xFFC0263A)), Mood("Sad", "sad songs", Color(0xFF4C5D73)),
    Mood("Sleep", "sleep music", Color(0xFF253A6B)), Mood("Workout", "workout music", Color(0xFFE0562B)),
    Mood("African", "african music", Color(0xFF237442)), Mood("Arabic", "arabic music", Color(0xFF9E572D)),
    Mood("Autumn", "autumn instrumental music", Color(0xFF625A45)), Mood("Blues", "blues music", Color(0xFF2760A7)),
    Mood("Bollywood & Indian", "bollywood music", Color(0xFF713CAF)), Mood("Classical", "classical music", Color(0xFF66666A)),
    Mood("Country & Americana", "country music", Color(0xFF7A542B)), Mood("Dance", "dance music", Color(0xFF168E9B)),
)
private val HomeMoods = Moods.take(8)

private data class BrowseShortcut(val label: String, val query: String)
private val ExploreShortcuts = listOf(
    BrowseShortcut("New music", "new music"),
    BrowseShortcut("Top songs", "top songs"),
    BrowseShortcut("90s hits", "90s hits"),
    BrowseShortcut("Rock", "rock music"),
    BrowseShortcut("Electronic", "electronic music"),
    BrowseShortcut("Soundtracks", "movie soundtracks"),
)

@Composable
private fun BrowseShortcutChip(shortcut: BrowseShortcut, onClick: (String) -> Unit) =
    Box(
        Modifier.clip(CircleShape).background(NuvxColors.Card).focusRing(CircleShape)
            .clickable(onClickLabel = "Search ${shortcut.label}") { onClick(shortcut.query) }
            .semantics { role = Role.Button; contentDescription = shortcut.label }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(shortcut.label, color = NuvxColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }

/** Mood tiles: a colour band with real artwork (the mood's top song) fading in from the right; the colour alone if none loads. */
@Composable fun ExploreScreen(
    onMood: (String) -> Unit,
    router: BackendRouter? = null,
    androidUi: Boolean = false,
    recommendationSeed: Track? = null,
    onPlayRecommendations: ((List<Track>, Int) -> Unit)? = null,
    onAccount: () -> Unit,
) {
    // shortcut: artwork is cached only while Explore is on screen; hoist to the app graph if re-fetching on return is noticeable.
    val desktop = LocalDesktopLayout.current
    val art = remember { mutableStateMapOf<String, String?>() }
    var recommendations by remember(router, recommendationSeed?.videoId) { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(router, recommendationSeed?.videoId, androidUi) {
        if (androidUi && router != null && recommendationSeed != null && !recommendationSeed.isLocal) {
            recommendations = try {
                router.radio(recommendationSeed.videoId).tracks
                    .filter { it.videoId != recommendationSeed.videoId }
                    .distinctBy { it.videoId }.take(20)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }
    if (desktop) {
        LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(Moods.chunked(4)) { row ->
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(row, key = Mood::query) { mood -> MoodTile(mood, art, router, Modifier.width(232.dp).height(136.dp), desktop, onMood) }
                }
            }
        }
    } else {
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = desktopAwareBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    TopBar(onAccount)
                    LargeTitle("Explore", Modifier.padding(bottom = 4.dp))
                    if (androidUi) {
                        Text("Find something for the moment.", color = NuvxColors.Secondary, fontSize = 15.sp,
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
                        SectionHeader("Browse")
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ExploreShortcuts, key = BrowseShortcut::query) { BrowseShortcutChip(it, onMood) }
                        }
                    }
                }
            }
            if (androidUi && recommendations.isNotEmpty() && onPlayRecommendations != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionHeader("Recommended for you")
                        Shelf(recommendations) { index -> onPlayRecommendations(recommendations, index) }
                    }
                }
            }
            if (androidUi) item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Moods & genres") }
            items(Moods, key = Mood::query) { mood -> MoodTile(mood, art, router, Modifier.fillMaxWidth().aspectRatio(1.6f), desktop, onMood) }
        }
    }
}

@Composable
private fun MoodTile(
    mood: Mood,
    art: MutableMap<String, String?>,
    router: BackendRouter?,
    modifier: Modifier,
    desktop: Boolean,
    onMood: (String) -> Unit,
) {
    LaunchedEffect(mood.query) {
        if (router != null && mood.query !in art) art[mood.query] = try {
            val urls = router.search(mood.query, SearchFilter.SONGS).tracks.mapNotNull { it.artworkUrl }
            urls.firstOrNull { it !in art.values } ?: urls.firstOrNull()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val artworkUrl = art[mood.query]
    val corner = if (desktop) 16.dp else 20.dp
    Box(modifier.clip(RoundedCornerShape(corner)).background(mood.colour).focusRing(RoundedCornerShape(corner))
        .clickable(onClickLabel = "Search ${mood.label}") { onMood(mood.query) }) {
        artworkUrl?.let { url ->
            SharpArtwork(url, null, Modifier.matchParentSize(), 544)
            Box(Modifier.matchParentSize().background(mood.colour.copy(alpha = 0.24f)))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to mood.colour, 0.42f to mood.colour.copy(alpha = 0.44f), 1f to mood.colour.copy(alpha = 0.16f))))
        }
        Text(mood.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun LibraryScreen(history: History, state: PlayerState, player: NuvxPlayer, onLiked: () -> Unit, onAccount: () -> Unit, onLocalLibrary: (() -> Unit)? = null, motionEnabled: Boolean = true, androidUi: Boolean = false) {
    val played by history.played.collectAsState()
    val liked by history.liked.collectAsState()
    val desktop = LocalDesktopLayout.current
    LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        if (!desktop) item { TopBar(onAccount) }
        if (!desktop) item { LargeTitle(if (androidUi) "Your Library" else "Library", Modifier.padding(bottom = 12.dp)) }
        if (desktop) item {
            val latest = played.firstOrNull()
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DesktopLibraryCard(
                        value = played.size.toString(),
                        label = if (played.size == 1) "TRACK IN RECENT HISTORY" else "TRACKS IN RECENT HISTORY",
                        detail = "ON THIS DEVICE · UP TO 50 TRACKS",
                    )
                }
                item {
                    DesktopLibraryCard(
                        value = latest?.artists?.firstOrNull() ?: "Nothing yet",
                        label = "MOST RECENT ARTIST",
                        detail = if (latest == null) "PLAY A TRACK TO FILL THIS CARD" else "FROM YOUR RECENT HISTORY",
                    )
                }
                item {
                    DesktopLibraryCard(
                        value = latest?.title ?: "Nothing played yet",
                        label = "MOST RECENT TRACK",
                        detail = if (latest == null) "PLAY A TRACK TO FILL THIS CARD" else "FROM YOUR RECENT HISTORY",
                    )
                }
            }
        } else item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A2C33), Color(0xFF16171B)))).padding(20.dp)) {
                Text("YOUR LISTENING", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                Text("${played.size}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text(if (played.size == 1) "SONG PLAYED ON THIS DEVICE" else "SONGS PLAYED ON THIS DEVICE", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, letterSpacing = 1.5.sp)
            }
        }
        if (desktop) {
            item { DesktopLibraryAction("Liked songs", onLiked, "heart.fill", motionEnabled) }
            if (onLocalLibrary != null) {
                item { DesktopLibraryDivider() }
                item { DesktopLibraryAction("On this device", onLocalLibrary, "iphone", motionEnabled) }
            }
        } else {
            item { NavRow(NuvxIcons.HeartFilled, "Liked songs", if (liked.isEmpty()) "None yet" else songs(liked.size), onLiked, "heart.fill", motionEnabled) }
            if (onLocalLibrary != null) item { NavRow(NuvxIcons.Library, "On this device", "Music stored here", onLocalLibrary, "iphone", motionEnabled) }
        }
        if (played.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            if (androidUi && !desktop) {
                item { Shelf(played.take(12), size = 152.dp) { player.playQueue(played, it) } }
            } else {
                itemsIndexed(played) { i, t -> TrackRow(t, active = state.current?.videoId == t.videoId) { player.playQueue(played, i) } }
            }
        }
    }
}

@Composable
private fun DesktopLibraryCard(value: String, label: String, detail: String) {
    Column(
        Modifier.width(300.dp).height(190.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF27313D), Color(0xFF15171B))))
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR LISTENING EXPERIENCE", color = Color(0xFFD2D4D8), fontSize = 11.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            NuvxBrandMark(null, Modifier.size(30.dp).padding(start = 8.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            Text(value, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(label, color = Color(0xFFD2D4D8), fontSize = 10.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NUVX LISTENER", color = Color(0xFFD2D4D8), fontSize = 9.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Color(0xFFB0B3B9), fontSize = 8.sp, letterSpacing = 0.5.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DesktopLibraryAction(label: String, onClick: () -> Unit, symbolName: String, motionEnabled: Boolean) {
    var taps by remember { mutableIntStateOf(0) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable {
            if (motionEnabled) taps++
            onClick()
        }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvxGlyphIcon(symbolName, Modifier.size(20.dp), tint = NuvxColors.Text, trigger = taps, animateOnTrigger = true, motionEnabled = motionEnabled)
        Text(label, color = NuvxColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 16.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp), tint = NuvxColors.Tertiary)
    }
}

@Composable
private fun DesktopLibraryDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
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

@Composable fun SearchScreen(router: BackendRouter, player: NuvxPlayer, history: History, initialQuery: String?, onOpen: (Collection) -> Unit, onAccount: () -> Unit, androidUi: Boolean = false, onSubmittedQuery: (String?) -> Unit = {}) {
    val desktop = LocalDesktopLayout.current
    var query by remember(initialQuery) { mutableStateOf(initialQuery.orEmpty()) }
    var submitted by remember(initialQuery) { mutableStateOf(initialQuery) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var tops by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents by history.searches.collectAsState()
    val discoveryArt = remember { mutableStateMapOf<String, String?>() }
    val focus = remember { FocusRequester() }
    fun submit(value: String) {
        value.trim().takeIf(String::isNotEmpty)?.let { submitted = it; searchRequest++; onSubmittedQuery(it) }
    }
    LaunchedEffect(Unit) { if (initialQuery == null && !desktop) runCatching { focus.requestFocus() } }
    LaunchedEffect(submitted, filter, searchRequest) {
        val q = submitted ?: run {
            busy = false; error = null; results = emptyList(); albums = emptyList(); tops = emptyList()
            return@LaunchedEffect
        }
        busy = true; error = null; results = emptyList(); albums = emptyList(); tops = emptyList()
        try {
            val found = if (filter == SearchFilter.VIDEOS) router.searchVideos(q) else router.search(q, filter)
            when (filter) {
                SearchFilter.ALBUMS -> albums = found.collections.filter { it.kind == Collection.Kind.ALBUM }
                SearchFilter.PLAYLISTS -> albums = found.collections.filter { it.kind == Collection.Kind.PLAYLIST }
                SearchFilter.ARTISTS -> albums = found.collections.filter { it.kind == Collection.Kind.ARTIST }
                else -> { results = found.tracks; if (filter == SearchFilter.ALL) tops = found.collections.take(12) }
            }
            history.searched(q)
            if (results.isEmpty() && albums.isEmpty() && tops.isEmpty()) error = "Nothing found for \"$q\"."
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Search isn't available right now. Check your connection, or try the other engine in Settings." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        if (!desktop) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).glass().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Search, null, Modifier.size(20.dp), tint = NuvxColors.Secondary)
                    Box(Modifier.weight(1f).padding(start = 10.dp)) {
                        if (query.isEmpty()) Text("Artists, songs and more", color = NuvxColors.Tertiary, fontSize = 17.sp)
                        BasicTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" }, singleLine = true,
                            textStyle = TextStyle(color = NuvxColors.Text, fontSize = 17.sp), cursorBrush = SolidColor(NuvxColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(query) }))
                    }
                    if (query.isNotEmpty()) PlainIconButton(NuvxIcons.Close, "Clear search", 44.dp) { query = ""; submitted = null; onSubmittedQuery(null); results = emptyList(); error = null }
                }
                GlassIconButton(NuvxIcons.Person, "Settings and about", onClick = onAccount)
            }
        }
        FilterChips(filter) { filter = it }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f))
        LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
            if (submitted == null) {
                if (recents.isNotEmpty()) {
                    item { SectionHeader("Recent searches") { Text("Clear", color = NuvxColors.Text, fontSize = 16.sp, modifier = Modifier.clickable { history.clearSearches() }) } }
                    items(recents) { q ->
                        Row(Modifier.fillMaxWidth().clickable { query = q; submit(q) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NuvxIcons.Search, null, Modifier.size(18.dp), tint = NuvxColors.Secondary)
                            Text(q, color = NuvxColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PlainIconButton(NuvxIcons.Close, "Remove $q from recent searches", 44.dp) { history.forgetSearch(q) }
                        }
                    }
                } else if (!androidUi) item { Text("Find songs, artists and moods.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) }
                if (androidUi) {
                    item { SectionHeader("Browse") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ExploreShortcuts, key = BrowseShortcut::query) { shortcut ->
                                BrowseShortcutChip(shortcut) { value -> query = value; submit(value) }
                            }
                        }
                    }
                    item { SectionHeader("Moods & genres") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(HomeMoods, key = Mood::query) { mood ->
                                MoodTile(mood, discoveryArt, router, Modifier.width(168.dp).height(108.dp), desktop) { value ->
                                    query = value
                                    submit(value)
                                }
                            }
                        }
                    }
                }
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

@Composable private fun CoverArt(url: String?, title: String, modifier: Modifier, size: Int = 226) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(NuvxColors.Card)) {
        if (url != null) SharpArtwork(url, "Cover of $title", Modifier.fillMaxSize(), size)
    }

/** Album page: big cover, title, artist and year, Play, then the numbered track list. */
@Composable fun AlbumPage(router: BackendRouter, player: NuvxPlayer, state: PlayerState, ref: Collection) {
    var album by remember(ref.browseId) { mutableStateOf<Album?>(null) }
    var failed by remember(ref.browseId) { mutableStateOf(false) }
    LaunchedEffect(ref.browseId) {
        try { album = router.album(ref.browseId) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val a = album
    LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(a?.artworkUrl ?: ref.artworkUrl, ref.title, Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f), 1080)
                Text(a?.title ?: ref.title, color = NuvxColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Text(listOfNotNull(a?.artist, a?.year).joinToString(" · ").ifEmpty { ref.subtitle.orEmpty() }, color = NuvxColors.Accent, fontSize = 17.sp)
                if (a != null && a.tracks.isNotEmpty()) PlayShuffle(player, a.tracks)
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

/** More menu on a catalogue row: open a video externally, insert next, or add to the queue. */
@Composable fun TrackMenu(player: NuvxPlayer, track: Track) {
    var open by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val videoUrl = MusicVideoMatcher.youtubeWatchUrl(track)
    Box {
        PlainIconButton(NuvxIcons.More, "More options for ${track.title}", 44.dp) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Color(0xFF232327)) {
            videoUrl?.let { url ->
                androidx.compose.material3.DropdownMenuItem({ Text("Watch on YouTube") }, { open = false; uri.openUri(url) })
            }
            androidx.compose.material3.DropdownMenuItem({ Text("Play next") }, { open = false; player.playNext(listOf(track)) })
            androidx.compose.material3.DropdownMenuItem({ Text("Add to queue") }, { open = false; player.enqueue(listOf(track)) })
        }
    }
}

/** Play in order (white, primary) beside Shuffle (glass, secondary) for an album or artist. */
@Composable fun PlayShuffle(player: NuvxPlayer, tracks: List<Track>) =
    Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.clip(CircleShape).background(Color.White).focusRing().clickable { player.playQueue(tracks, 0) }
            .semantics { role = Role.Button }.padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NuvxIcons.Play, null, Modifier.size(20.dp), tint = Color.Black)
            Text("  Play", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.glass(CircleShape).focusRing().clickable { player.playQueue(tracks.shuffled(), 0) }
            .semantics { role = Role.Button }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NuvxIcons.Shuffle, null, Modifier.size(20.dp), tint = Color.White)
            Text("  Shuffle", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
