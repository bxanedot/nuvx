package com.bxane.nuvx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.hiResArtwork

object NuvxColors {
    val Background = Color.Black
    val Text = Color.White
    val Secondary = Color.White.copy(alpha = 0.6f)
    val Tertiary = Color.White.copy(alpha = 0.56f) // 5.9–6.5:1 on our surfaces; placeholders need 4.5:1
    val Card = Color(0xFF1A1A1C)
    val Accent = Color(0xFF67ACFF) // from bxane's electric-blue mark
}

/**
 * Frosted-glass look: translucent dark fill plus a hairline light border. Compose can't blur what's *behind* a
 * layer portably, so the fill is dense enough to stay legible over artwork.
 */
fun Modifier.glass(shape: Shape = CircleShape) = this
    .clip(shape)
    .background(Color(0xFF26262A).copy(alpha = 0.86f), shape)
    .border(0.7.dp, Color.White.copy(alpha = 0.14f), shape)

@Composable fun LargeTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(horizontal = 20.dp), color = NuvxColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)

@Composable fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = NuvxColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        action?.invoke()
    }

/** Visible keyboard/D-pad focus: a white ring, so TV remotes and keyboards can see where they are. Must precede the clickable. */
@Composable fun Modifier.focusRing(shape: Shape = CircleShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.border(if (focused) 2.5.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}

@Composable fun GlassIconButton(icon: ImageVector, label: String, size: Dp = 48.dp, onClick: () -> Unit) =
    Box(Modifier.size(size).glass().focusRing().clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.46f), tint = NuvxColors.Text)
    }

@Composable fun PlainIconButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).focusRing().clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.62f), tint = if (enabled) NuvxColors.Text else Color.White.copy(alpha = 0.3f))
    }

/** Floating pill: artwork · title/artist · play/pause · next. Tapping the text opens the full player. */
@Composable fun MiniPlayerPill(state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, onPrevious: () -> Unit = {}) {
    val track = state.current ?: return
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).trackSwipe(onNext = onNext, onPrevious = onPrevious, onUp = onOpen)
        .glass(RoundedCornerShape(32.dp)).clickable(onClick = onOpen).padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // While the full player is open its cover stands in for this one; the two morph into each other (see ArtMorph).
        val morph = LocalArtMorph.current
        Box(Modifier.size(width = 52.dp, height = 44.dp)) {
            if (morph == null) ArtworkTile(track, Modifier.fillMaxSize())
            else androidx.compose.animation.AnimatedVisibility(visible = !morph.open, enter = fadeIn(), exit = fadeOut()) { ArtworkTile(track, Modifier.fillMaxSize().morphArt(this)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = NuvxColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (state.buffering) "Loading…" else track.artistLine, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlainIconButton(if (state.isPlaying) NuvxIcons.Pause else NuvxIcons.Play, if (state.isPlaying) "Pause" else "Play", 44.dp, onClick = onToggle)
        PlainIconButton(NuvxIcons.Forward, "Next", 44.dp, enabled = state.index < state.queue.lastIndex, onClick = onNext)
    }
}

data class Tab(val label: String, val icon: ImageVector)

/** Glass capsule of tabs with the selected one in a lighter inner pill, plus a separate round search button. */
@Composable fun TabBar(tabs: List<Tab>, selectedIndex: Int, searchSelected: Boolean, onSelect: (Int) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 66.dp).height(IntrinsicSize.Min).glass(RoundedCornerShape(33.dp)).padding(5.dp)) {
            tabs.forEachIndexed { i, tab ->
                val active = i == selectedIndex && !searchSelected
                val bg by animateColorAsState(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(bg).clickable { onSelect(i) }
                    .semantics { selected = active; role = Role.Tab; contentDescription = tab.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(tab.icon, null, Modifier.size(24.dp), tint = if (active) NuvxColors.Text else NuvxColors.Secondary)
                    Text(tab.label, fontSize = 11.sp, color = if (active) NuvxColors.Text else NuvxColors.Secondary, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(Modifier.size(66.dp).glass().background(if (searchSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable(onClick = onSearch)
            .semantics { contentDescription = "Search"; role = Role.Tab; selected = searchSelected }, contentAlignment = Alignment.Center) {
            Icon(NuvxIcons.Search, null, Modifier.size(28.dp), tint = NuvxColors.Text)
        }
    }
}

/** Leading icon · label · chevron row, as used in Library and Settings lists. */
@Composable fun NavRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = NuvxColors.Text)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(label, color = NuvxColors.Text, fontSize = 17.sp)
            detail?.let { Text(it, color = NuvxColors.Secondary, fontSize = 13.sp) }
        }
        Icon(NuvxIcons.Chevron, null, Modifier.size(18.dp), tint = NuvxColors.Tertiary)
    }

/**
 * Swipe left for the next track, right for the previous one, and optionally up (e.g. to open the player).
 * The content follows the finger a little as feedback; buttons stay available for people who don't swipe.
 */
@Composable fun Modifier.trackSwipe(onNext: () -> Unit, onPrevious: () -> Unit, onUp: (() -> Unit)? = null): Modifier {
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    return this
        .graphicsLayer { translationX = dx * 0.35f; translationY = minOf(0f, dy) * 0.2f }
        .pointerInput(onNext, onPrevious, onUp) {
            // Without a swipe-up action only sideways drags are taken, so vertical drags reach the parent (e.g. pull the player down to close).
            if (onUp == null) detectHorizontalDragGestures(
                onDragEnd = {
                    if (dx < -threshold) onNext() else if (dx > threshold) onPrevious()
                    dx = 0f
                },
                onDragCancel = { dx = 0f },
            ) { change, drag -> change.consume(); dx += drag }
            else detectDragGestures(
                onDragEnd = {
                    when {
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -threshold -> onNext()
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > threshold -> onPrevious()
                        onUp != null && -dy > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> onUp()
                    }
                    dx = 0f; dy = 0f
                },
                onDragCancel = { dx = 0f; dy = 0f },
            ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
        }
}

/**
 * Long-press a row and drag it to a new place in a list; screen readers get Move up / Move down actions instead.
 * The row lifts and follows the finger; the move happens on release, by whole row heights.
 */
@Composable fun Modifier.reorderable(index: Int, lastIndex: Int, onMove: (from: Int, to: Int) -> Unit): Modifier {
    var dy by remember { mutableStateOf(0f) }
    var height by remember { mutableStateOf(1) }
    val at by rememberUpdatedState(index)
    val last by rememberUpdatedState(lastIndex)
    val move by rememberUpdatedState(onMove)
    return this
        .onSizeChanged { height = it.height.coerceAtLeast(1) }
        .zIndex(if (dy != 0f) 1f else 0f)
        .graphicsLayer { translationY = dy; if (dy != 0f) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f } }
        .background(if (dy != 0f) NuvxColors.Card else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics {
            customActions = listOfNotNull(
                if (index > 0) CustomAccessibilityAction("Move up") { onMove(index, index - 1); true } else null,
                if (index < lastIndex) CustomAccessibilityAction("Move down") { onMove(index, index + 1); true } else null,
            )
        }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragEnd = {
                    val to = (at + kotlin.math.round(dy / height).toInt()).coerceIn(0, last)
                    if (to != at) move(at, to)
                    dy = 0f
                },
                onDragCancel = { dy = 0f },
            ) { change, drag -> change.consume(); dy += drag.y }
        }
}

/** Artwork at about [size] px; if that larger variant does not exist it falls back to the original URL. */
@Composable fun SharpArtwork(url: String?, description: String?, modifier: Modifier, size: Int = 226) {
    var failed by remember(url) { mutableStateOf(false) }
    coil3.compose.AsyncImage(if (failed) url else hiResArtwork(url, size), description, modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop, onError = { failed = true })
}

/** Set by NuvxApp on phones (not with reduced motion): the mini player's cover grows into the full player's cover and back. */
@OptIn(ExperimentalSharedTransitionApi::class)
class ArtMorph(val scope: SharedTransitionScope, val open: Boolean)
val LocalArtMorph = compositionLocalOf<ArtMorph?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun Modifier.morphArt(visibility: AnimatedVisibilityScope?): Modifier {
    val morph = LocalArtMorph.current
    if (morph == null || visibility == null) return this
    return with(morph.scope) { this@morphArt.sharedElement(rememberSharedContentState("nowPlayingArt"), visibility) }
}
