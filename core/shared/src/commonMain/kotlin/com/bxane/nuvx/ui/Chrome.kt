package com.bxane.nuvx.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

object NuvxColors {
    val Background = Color.Black
    val Text = Color.White
    val Secondary = Color.White.copy(alpha = 0.6f)
    val Tertiary = Color.White.copy(alpha = 0.38f)
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

@Composable fun GlassIconButton(icon: ImageVector, label: String, size: Dp = 48.dp, onClick: () -> Unit) =
    Box(Modifier.size(size).glass().clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.46f), tint = NuvxColors.Text)
    }

@Composable fun PlainIconButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.62f), tint = if (enabled) NuvxColors.Text else NuvxColors.Tertiary)
    }

/** Floating pill: artwork · title/artist · play/pause · next. Tapping the text opens the full player. */
@Composable fun MiniPlayerPill(state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    Row(modifier.fillMaxWidth().height(64.dp).glass(RoundedCornerShape(32.dp)).clickable(onClick = onOpen).padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtworkTile(track, Modifier.size(width = 52.dp, height = 44.dp))
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
        Row(Modifier.weight(1f).height(66.dp).glass(RoundedCornerShape(33.dp)).padding(5.dp)) {
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
