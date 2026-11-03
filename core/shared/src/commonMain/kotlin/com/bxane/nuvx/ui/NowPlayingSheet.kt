package com.bxane.nuvx.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Dim = Color.White.copy(alpha = 0.38f)

private enum class SheetMode { ARTWORK, LYRICS, QUEUE }

/** Full-screen player: blurred-artwork backdrop with artwork, lyrics and queue modes. Swipe down or tap the handle to close. */
@Composable
fun NowPlayingSheet(player: NuvxPlayer, state: PlayerState, library: LyricsChain, history: History, router: BackendRouter, reduceMotion: Boolean, onOpen: (Collection) -> Unit = {}, onClose: () -> Unit) {
    val track = state.current
    val liked by history.liked.collectAsState()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    val actions = TrackActions(
        liked = track != null && liked.any { it.videoId == track.videoId },
        onLike = { track?.let(history::toggleLike) },
        openAlbum = track?.albumId?.let { id -> { onOpen(Collection(Collection.Kind.ALBUM, id, track.album ?: track.title, null, track.artworkUrl)) } },
        openArtist = track?.artistId?.let { id -> { onOpen(Collection(Collection.Kind.ARTIST, id, track.artists.firstOrNull().orEmpty(), null, null)) } },
        onRadio = {
            track?.let { seed ->
                scope.launch {
                    notice = "Starting radio…"
                    notice = try {
                        val page = router.radio(seed.videoId)
                        player.playQueue(listOf(seed) + page.tracks.filter { it.videoId != seed.videoId }, 0)
                        null
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { "Radio isn't available right now." }
                }
            }
        },
    )
    var mode by remember { mutableStateOf(SheetMode.ARTWORK) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var source by remember { mutableStateOf<SourcedLyrics?>(null) }
    val uri = LocalUriHandler.current
    var status by remember { mutableStateOf("") }
    // Match lyrics against the length of the version actually playing; platform players may not carry it in metadata.
    val lookupDuration = track?.durationMs ?: state.durationMs.takeIf { it > 0 }
    LaunchedEffect(track?.videoId, lookupDuration != null) {
        lyrics = null
        source = null
        if (track == null) return@LaunchedEffect
        status = "Finding lyrics…"
        if (lookupDuration == null) return@LaunchedEffect // re-runs once the player reports the length
        try { val found = library.find(track.copy(durationMs = lookupDuration)); lyrics = found?.lyrics; source = found; if (found == null) status = "No lyrics for this song" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Lyrics couldn't be loaded" }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectVerticalDragGestures(onDragEnd = { if (drag > 180f) onClose(); drag = 0f }) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
    }) {
        // The artwork itself, blurred and darkened, is the colour wash; no palette extraction needed.
        if (track?.artworkUrl != null) AsyncImage(track.artworkUrl, null, Modifier.fillMaxSize().blur(90.dp).alpha(0.75f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.35f), Color.Black.copy(0.55f), Color.Black.copy(0.8f)))))
        Column(Modifier.safeDrawingPadding().fillMaxSize().widthIn(max = 640.dp).align(Alignment.TopCenter).padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClose).semantics { contentDescription = "Close player" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(0.45f)))
            }
            Text(if (mode == SheetMode.QUEUE) "Playing next" else "Playing from your queue", color = Color.White.copy(0.75f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center)
            if (mode != SheetMode.ARTWORK) CompactHeader(track, actions)
            if (mode == SheetMode.LYRICS) source?.let { s ->
                Text("Lyrics from ${s.source}" + if (s.link != null) " ↗" else "", color = Color.White.copy(0.55f), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp).then(if (s.link != null) Modifier.clickable { uri.openUri(s.link) }.semantics { role = Role.Button } else Modifier))
            }
            notice?.let { Text(it, color = Color.White.copy(0.75f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    SheetMode.ARTWORK -> ArtworkMode(track, lyrics, state.positionMs, actions, player::next, player::previous) { mode = SheetMode.LYRICS }
                    SheetMode.LYRICS -> when (val l = lyrics) {
                        is Lyrics.Synced -> SyncedLyrics(l, state.positionMs, reduceMotion) { player.seekTo(it) }
                        is Lyrics.Plain -> LazyColumn(contentPadding = PaddingValues(vertical = 24.dp)) {
                            item { Text(l.text, color = Color.White.copy(0.85f), fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp) }
                        }
                        Lyrics.Instrumental -> Centered("♪  Instrumental")
                        null -> Centered(if (track == null) "Play something to see it here." else status)
                    }
                    SheetMode.QUEUE -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                        itemsIndexed(state.queue) { i, t ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.skipTo(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkTile(t, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(t.title, color = if (i == state.index) NuvxColors.Accent else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artistLine, color = Color.White.copy(0.6f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (i != state.index) PlainIconButton(NuvxIcons.Close, "Remove ${t.title} from the queue", 44.dp) { player.remove(i) }
                            }
                        }
                    }
                }
            }
            Transport(player, state)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ModeButton(NuvxIcons.Lyrics, "Lyrics", mode == SheetMode.LYRICS) { mode = if (mode == SheetMode.LYRICS) SheetMode.ARTWORK else SheetMode.LYRICS }
                ModeButton(NuvxIcons.Queue, "Queue", mode == SheetMode.QUEUE) { mode = if (mode == SheetMode.QUEUE) SheetMode.ARTWORK else SheetMode.QUEUE }
            }
        }
    }
}

@Composable private fun ModeButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).background(if (active) Color.White.copy(0.2f) else Color.Transparent).clickable(onClick = onClick)
        .semantics { contentDescription = label; selected = active }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = Color.White.copy(if (active) 1f else 0.7f))
    }

@Composable private fun CompactHeader(track: Track?, actions: TrackActions) = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
    ArtworkTile(track, Modifier.size(56.dp))
    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(track?.title ?: "Nothing playing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.linkTo(actions.openAlbum, "Open album"))
        Text(track?.artistLine.orEmpty(), color = Color.White.copy(0.6f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.linkTo(actions.openArtist, "Open artist"))
    }
    TrackChips(track != null, actions)
}

@Composable private fun ArtworkMode(track: Track?, lyrics: Lyrics?, positionMs: Long, actions: TrackActions, onNext: () -> Unit, onPrevious: () -> Unit, openLyrics: () -> Unit) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    ArtworkTile(track, Modifier.weight(1f, fill = false).aspectRatio(1f).fillMaxWidth().trackSwipe(onNext, onPrevious).clip(RoundedCornerShape(14.dp)))
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: "Nothing playing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.linkTo(actions.openAlbum, "Open album"))
            Text(track?.artistLine.orEmpty(), color = Color.White.copy(0.6f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.linkTo(actions.openArtist, "Open artist"))
        }
        TrackChips(track != null, actions)
    }
    val preview = (lyrics as? Lyrics.Synced)?.let { l -> l.lines.getOrNull(l.lineAt(positionMs).coerceAtLeast(0))?.text }
    if (!preview.isNullOrBlank()) Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable(onClick = openLyrics), verticalAlignment = Alignment.CenterVertically) {
        Text(preview, color = Color.White.copy(0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(NuvxIcons.Chevron, "Open lyrics", Modifier.size(16.dp), tint = Color.White.copy(0.6f))
    }
}

@Composable private fun Centered(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = Color.White.copy(0.6f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

private class TrackActions(
    val liked: Boolean, val onLike: () -> Unit, val onRadio: () -> Unit,
    /** Set only when the track carries a browse ID, so the title and artist are tappable exactly when there is somewhere to go. */
    val openAlbum: (() -> Unit)? = null, val openArtist: (() -> Unit)? = null,
)

private fun Modifier.linkTo(open: (() -> Unit)?, label: String) =
    if (open == null) this else clickable(onClickLabel = label, onClick = open)

/** Round like chip and a more chip with a menu. Likes are on-device until account sync exists. */
@Composable private fun TrackChips(enabled: Boolean, a: TrackActions) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundChip(if (a.liked) NuvxIcons.HeartFilled else NuvxIcons.Heart, if (a.liked) "Remove from liked songs" else "Like", enabled, a.onLike)
        Box {
            RoundChip(NuvxIcons.More, "More options", enabled) { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = Color(0xFF232327)) {
                DropdownMenuItem({ Text("Start radio from this song") }, { menu = false; a.onRadio() })
                DropdownMenuItem({ Text(if (a.liked) "Remove from liked songs" else "Add to liked songs") }, { menu = false; a.onLike() })
            }
        }
    }
}

@Composable private fun RoundChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.16f)).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White.copy(if (enabled) 1f else 0.4f))
    }
}

@Composable fun ArtworkTile(track: Track?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C2230)), contentAlignment = Alignment.Center) {
        if (track?.artworkUrl != null) AsyncImage(track.artworkUrl, "Artwork for ${track.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Active line white with words lighting up as they're sung; others dimmed and blurred by distance; tap a line to seek. */
@Composable private fun SyncedLyrics(l: Lyrics.Synced, positionMs: Long, reduceMotion: Boolean, seek: (Long) -> Unit) {
    val active = l.lineAt(positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        if (reduceMotion) list.scrollToItem(active) else list.animateScrollToItem(active, scrollOffset = -120)
    }
    LazyColumn(state = list, contentPadding = PaddingValues(top = 120.dp, bottom = 320.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        itemsIndexed(l.lines) { i, line ->
            val distance = if (active < 0) 2 else abs(i - active)
            val blur by animateFloatAsState(if (reduceMotion) 0f else (distance * 1.6f).coerceAtMost(6f), tween(400))
            val text = buildAnnotatedString {
                // Each word fills from dim to white across its own duration, so the highlight glides through the line.
                if (i == active && line.words.isNotEmpty()) line.words.forEachIndexed { wi, w ->
                    withStyle(SpanStyle(color = androidx.compose.ui.graphics.lerp(Dim, Color.White, l.wordProgress(i, wi, positionMs)))) { append(w.text) }
                } else withStyle(SpanStyle(color = if (i == active) Color.White else Dim)) { append(line.text.ifBlank { "♪" }) }
            }
            Text(text, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
                modifier = Modifier.fillMaxWidth().clickable { seek(line.startMs) }.blur(blur.dp))
        }
    }
}

@Composable private fun Transport(player: NuvxPlayer, state: PlayerState) {
    val duration = state.durationMs.coerceAtLeast(1)
    Column(Modifier.padding(bottom = 16.dp)) {
        ThinProgress(state.positionMs, state.durationMs, player::seekTo)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(state.positionMs), color = Color.White.copy(0.6f), fontSize = 12.sp)
            Text("-" + clock((state.durationMs - state.positionMs).coerceAtLeast(0)), color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PlainIconButton(NuvxIcons.Back, "Previous", 64.dp) { player.previous() }
            PlainIconButton(if (state.isPlaying) NuvxIcons.Pause else NuvxIcons.Play, if (state.isPlaying) "Pause" else "Play", 80.dp) { player.togglePlay() }
            PlainIconButton(NuvxIcons.Forward, "Next", 64.dp, enabled = state.index < state.queue.lastIndex) { player.next() }
        }
    }
}

/** Slim seek bar: tap or drag anywhere on it; thickens while dragging. Exposes range info and a set-progress action to screen readers. */
@Composable private fun ThinProgress(positionMs: Long, durationMs: Long, seek: (Long) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(1) }
    val enabled = durationMs > 0
    val fraction = dragFraction ?: if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val height by animateDpAsState(if (dragFraction != null) 10.dp else 5.dp)
    Box(Modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (enabled) setProgress("Seek") { seek((it.coerceIn(0f, 1f) * durationMs).toLong()); true }
        }
        .pointerInput(enabled, durationMs) {
            if (!enabled) return@pointerInput
            detectTapGestures { seek((it.x / width * durationMs).toLong().coerceIn(0, durationMs)) }
        }
        .pointerInput(enabled, durationMs) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { dragFraction = (it.x / width).coerceIn(0f, 1f) },
                onDragEnd = { dragFraction?.let { f -> seek((f * durationMs).toLong()) }; dragFraction = null },
                onDragCancel = { dragFraction = null },
            ) { change, _ -> dragFraction = (change.position.x / width).coerceIn(0f, 1f) }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(0.25f)))
        Box(Modifier.fillMaxWidth(fraction).height(height).clip(CircleShape).background(Color.White.copy(if (dragFraction != null) 1f else 0.85f)))
    }
}

internal fun clock(ms: Long) = "${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
