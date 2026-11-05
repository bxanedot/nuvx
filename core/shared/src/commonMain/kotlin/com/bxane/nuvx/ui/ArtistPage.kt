package com.bxane.nuvx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Artist page with an artwork hero, playable tracks, and browsable shelves. */
@Composable fun ArtistPage(router: BackendRouter, player: NuvxPlayer, state: PlayerState, ref: Collection, onOpen: (Collection) -> Unit, reduceMotion: Boolean = false) {
    var artist by remember(ref.browseId) { mutableStateOf<Artist?>(null) }
    var failed by remember(ref.browseId) { mutableStateOf(false) }
    var loadRequest by remember(ref.browseId) { mutableStateOf(0) }
    var videoCategory by remember(ref.browseId) { mutableStateOf<ArtistVideoCategory?>(null) }
    var videoQuery by remember(ref.browseId) { mutableStateOf("") }
    var videoOrder by remember(ref.browseId) { mutableStateOf(ArtistVideoOrder.CATALOG) }
    var loadingVideoShelf by remember(ref.browseId) { mutableStateOf<String?>(null) }
    var failedVideoShelf by remember(ref.browseId) { mutableStateOf<String?>(null) }
    var consumedVideoContinuations by remember(ref.browseId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref.browseId, loadRequest) {
        artist = null
        failed = false
        try { artist = router.artist(ref.browseId) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val a = artist
    LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        item {
            ArtistHero(a?.name ?: ref.title, a?.artworkUrl ?: ref.artworkUrl)
        }
        when {
            failed -> item {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("This artist could not be loaded. Check your connection and try again.", color = NuvxColors.Secondary, fontSize = 15.sp)
                    TextButton(onClick = { loadRequest++ }) { Text("Try again") }
                }
            }
            a == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f)) }
            else -> {
                if (a.topSongs.isNotEmpty()) item { PlayShuffle(player, a.topSongs) }
                if (a.topSongs.isNotEmpty()) {
                    item { SectionHeader("Top songs") }
                    itemsIndexed(a.topSongs) { i, t -> TrackRow(t, active = state.current?.videoId == t.videoId, trailing = { TrackMenu(player, t) }) { player.playQueue(a.topSongs, i) } }
                }
                val videoCategories = a.videoShelves.map { it.category }.distinct()
                if (videoCategories.size > 1) {
                    item { ArtistVideoFilters(videoCategories, videoCategory) { videoCategory = it } }
                }
                if (a.videoShelves.isNotEmpty()) {
                    item {
                        val shown = remember(a.videoShelves, videoCategory, videoQuery) {
                            filterArtistVideoShelves(a.videoShelves, videoCategory).sumOf {
                                artistVideoItems(it.items, videoQuery, ArtistVideoOrder.CATALOG).size
                            }
                        }
                        ArtistVideoBrowserControls(videoQuery, { videoQuery = it }, videoOrder, { videoOrder = it },
                            shown, a.videoShelves.sumOf { it.items.size })
                    }
                    item {
                        AnimatedContent(
                            targetState = videoCategory,
                            transitionSpec = {
                                if (artistVideoFilterUsesTransition(reduceMotion)) {
                                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 24 } togetherWith
                                        fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 24 }).using(SizeTransform())
                                } else (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            },
                            label = "artistVideoFilter",
                        ) { category ->
                            Column {
                                filterArtistVideoShelves(a.videoShelves, category).forEach { shelf ->
                                    val visibleItems = remember(shelf.items, videoQuery, videoOrder) {
                                        artistVideoItems(shelf.items, videoQuery, videoOrder)
                                    }
                                    SectionHeader(shelf.title)
                                    if (visibleItems.isEmpty()) {
                                        Text("No loaded videos match this search.", color = NuvxColors.Secondary, fontSize = 14.sp,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                                    } else LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        itemsIndexed(visibleItems, key = { _, track -> track.videoId }) { index, track ->
                                            ArtistVideoCard(track, player) { player.playQueue(visibleItems, index) }
                                        }
                                    }
                                    if (shelf.continuation != null || shelf.moreBrowseId != null) {
                                        val shelfKey = "${shelf.category.name}:${shelf.title}"
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            TextButton(enabled = loadingVideoShelf == null, onClick = {
                                                scope.launch {
                                                    if (loadingVideoShelf != null) return@launch
                                                    loadingVideoShelf = shelfKey
                                                    failedVideoShelf = null
                                                    try {
                                                        val page = router.artistVideoPage(a.browseId, a.name, shelf)
                                                        val consumed = consumedVideoContinuations[shelfKey].orEmpty() + listOfNotNull(shelf.continuation)
                                                        artist = a.copy(videoShelves = a.videoShelves.map { current ->
                                                            if (current.title == shelf.title && current.category == shelf.category) {
                                                                appendArtistVideoPage(current, page, consumed)
                                                            } else current
                                                        })
                                                        consumedVideoContinuations = consumedVideoContinuations + (shelfKey to consumed)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {
                                                        failedVideoShelf = shelfKey
                                                    } finally {
                                                        loadingVideoShelf = null
                                                    }
                                                }
                                            }) {
                                                if (loadingVideoShelf == shelfKey) {
                                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                    Text("Loading videos…", modifier = Modifier.padding(start = 8.dp))
                                                }
                                                else Text(if (failedVideoShelf == shelfKey) "Retry loading videos" else "Load more videos")
                                            }
                                            if (failedVideoShelf == shelfKey) {
                                                Text("Could not load more videos. Check your connection and try again.", color = NuvxColors.Secondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                a.shelves.forEach { shelf ->
                    item { SectionHeader(shelf.title) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(shelf.items, key = { it.browseId }) { c -> CollectionCard(c) { onOpen(c) } }
                        }
                    }
                }
            }
        }
    }
}

internal fun artistVideoFilterUsesTransition(reduceMotion: Boolean) = !reduceMotion

internal enum class ArtistVideoOrder(val label: String) { CATALOG("Artist order"), TITLE("Title"), DURATION("Duration") }

/** Filters only loaded catalog metadata; source items and paging handles remain untouched. */
internal fun artistVideoItems(items: List<Track>, query: String, order: ArtistVideoOrder): List<Track> {
    val term = query.trim()
    val matches = if (term.isEmpty()) items else items.filter { "${it.title} ${it.artistLine}".contains(term, ignoreCase = true) }
    return when (order) {
        ArtistVideoOrder.CATALOG -> matches
        ArtistVideoOrder.TITLE -> matches.sortedBy { it.title.lowercase() }
        ArtistVideoOrder.DURATION -> matches.sortedBy { it.durationMs?.takeIf { duration -> duration > 0 } ?: Long.MAX_VALUE }
    }
}

@Composable private fun ArtistVideoBrowserControls(query: String, onQuery: (String) -> Unit, order: ArtistVideoOrder,
    onOrder: (ArtistVideoOrder) -> Unit, shown: Int, total: Int) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).glass().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NuvxIcons.Search, null, Modifier.size(20.dp), tint = NuvxColors.Secondary)
            Box(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                if (query.isEmpty()) Text("Search loaded videos", color = NuvxColors.Tertiary, fontSize = 15.sp)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().semantics { contentDescription = "Search loaded artist videos" },
                    singleLine = true, textStyle = TextStyle(color = NuvxColors.Text, fontSize = 15.sp), cursorBrush = SolidColor(NuvxColors.Accent))
            }
            if (query.isNotEmpty()) PlainIconButton(NuvxIcons.Close, "Clear video search", 48.dp) { onQuery("") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$shown of $total loaded videos", color = NuvxColors.Secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Sort artist videos" }) {
                    Text("Sort: ${order.label}", color = NuvxColors.Text)
                }
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Color(0xFF232327)) {
                    ArtistVideoOrder.entries.forEach { option ->
                        DropdownMenuItem({ Text(option.label) }, { sortMenu = false; onOrder(option) },
                            modifier = Modifier.semantics { selected = order == option })
                    }
                }
            }
        }
    }
}

@Composable private fun ArtistHero(name: String, artworkUrl: String?) =
    Box(Modifier.fillMaxWidth().height(320.dp).background(NuvxColors.Card)) {
        artworkUrl?.let { SharpArtwork(it, "Artist artwork for $name", Modifier.fillMaxSize(), 1080) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            0.38f to Color.Black.copy(alpha = 0.12f),
            1f to Color.Black.copy(alpha = 0.9f),
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(name, color = NuvxColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.7).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

@Composable private fun ArtistVideoCard(track: Track, player: NuvxPlayer, onPlay: () -> Unit) =
    Column(Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)).focusRing(RoundedCornerShape(14.dp))
        .clickable(onClickLabel = "Play video", onClick = onPlay).semantics { role = Role.Button }) {
        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)).background(NuvxColors.Card)) {
            track.artworkUrl?.let { SharpArtwork(it, "Video thumbnail for ${track.title}", Modifier.fillMaxSize(), 544) }
            Icon(NuvxIcons.Video, "Play video", Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp), tint = Color.White)
            track.durationMs?.takeIf { it > 0 }?.let { duration ->
                Text(videoDuration(duration), color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TrackMenu(player, track)
        }
    }

private fun videoDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = totalSeconds / 60
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

@Composable private fun ArtistVideoFilters(categories: List<ArtistVideoCategory>, selected: ArtistVideoCategory?, onSelect: (ArtistVideoCategory?) -> Unit) =
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min)
        .glass(RoundedCornerShape(20.dp)).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        ArtistVideoFilterChip("All", selected == null) { onSelect(null) }
        categories.forEach { category ->
            ArtistVideoFilterChip(category.displayName(), selected == category) { onSelect(category) }
        }
    }

@Composable private fun ArtistVideoFilterChip(label: String, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .background(if (selected) Color.White.copy(0.16f) else Color.Transparent)
        .focusRing(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .semantics { this.selected = selected; role = Role.Tab }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) NuvxColors.Text else NuvxColors.Secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }

private fun ArtistVideoCategory.displayName() = when (this) {
    ArtistVideoCategory.MUSIC_VIDEO -> "Music videos"
    ArtistVideoCategory.LIVE_PERFORMANCE -> "Live"
    ArtistVideoCategory.ACOUSTIC -> "Acoustic"
    ArtistVideoCategory.LYRIC_VIDEO -> "Lyric videos"
    ArtistVideoCategory.VISUALIZER -> "Visualizers"
    ArtistVideoCategory.INTERVIEW -> "Interviews"
    ArtistVideoCategory.BEHIND_THE_SCENES -> "Behind the scenes"
    ArtistVideoCategory.OTHER -> "Other"
}

@Composable internal fun CollectionCard(c: Collection, onClick: () -> Unit) =
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).focusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        val shape = if (c.kind == Collection.Kind.ARTIST) CircleShape else RoundedCornerShape(14.dp)
        Box(Modifier.size(150.dp).clip(shape).background(NuvxColors.Card)) {
            c.artworkUrl?.let { SharpArtwork(it, null, Modifier.fillMaxSize(), 544) }
        }
        Text(c.title, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        c.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
