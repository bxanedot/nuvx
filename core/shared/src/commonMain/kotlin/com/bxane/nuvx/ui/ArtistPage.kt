package com.bxane.nuvx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import kotlinx.coroutines.CancellationException

/** Artist page: round portrait, name, Play (top songs), the top songs list, then sideways shelves that open albums, playlists or artists. */
@Composable fun ArtistPage(router: BackendRouter, player: NuvxPlayer, state: PlayerState, ref: Collection, onOpen: (Collection) -> Unit) {
    var artist by remember(ref.browseId) { mutableStateOf<Artist?>(null) }
    var failed by remember(ref.browseId) { mutableStateOf(false) }
    LaunchedEffect(ref.browseId) {
        try { artist = router.artist(ref.browseId) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val a = artist
    LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(200.dp).clip(CircleShape).background(NuvxColors.Card)) {
                    (a?.artworkUrl ?: ref.artworkUrl)?.let { AsyncImage(it, "Photo of ${ref.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Text(a?.name ?: ref.title, color = NuvxColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                if (a != null && a.topSongs.isNotEmpty()) Row(Modifier.padding(vertical = 18.dp).clip(CircleShape).background(Color.White).focusRing()
                    .clickable { player.playQueue(a.topSongs, 0) }.semantics { role = Role.Button }.padding(horizontal = 32.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Play, null, Modifier.size(20.dp), tint = Color.Black)
                    Text("  Play", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        when {
            failed -> item { Text("This artist could not be loaded. Check your connection and try again.", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp)) }
            a == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f)) }
            else -> {
                if (a.topSongs.isNotEmpty()) {
                    item { SectionHeader("Top songs") }
                    itemsIndexed(a.topSongs) { i, t -> TrackRow(t, active = state.current?.videoId == t.videoId) { player.playQueue(a.topSongs, i) } }
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

@Composable private fun CollectionCard(c: Collection, onClick: () -> Unit) =
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).focusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        val shape = if (c.kind == Collection.Kind.ARTIST) CircleShape else RoundedCornerShape(14.dp)
        Box(Modifier.size(150.dp).clip(shape).background(NuvxColors.Card)) {
            c.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(c.title, color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        c.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
