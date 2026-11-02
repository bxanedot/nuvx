package com.bxane.nuvx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.LocalMusicLibrary
import com.bxane.nuvx.core.NuvxPlayer

@Composable
fun LocalLibraryScreen(library: LocalMusicLibrary, player: NuvxPlayer) {
    val libraryState by library.state.collectAsState()
    val playerState by player.state.collectAsState()
    LaunchedEffect(library) { library.open() }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("On this device", Modifier.padding(top = 12.dp, bottom = 8.dp))
        Text("Play audio stored on this device.", color = NuvxColors.Secondary, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(NuvxColors.Card).clickable(onClick = library::requestAccess).semantics { role = Role.Button }.padding(16.dp)) {
            Text(libraryState.actionLabel, color = NuvxColors.Text, fontSize = 16.sp)
        }
        libraryState.error?.let { Text(it, color = NuvxColors.Secondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        if (libraryState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = NuvxColors.Accent, trackColor = NuvxColors.Card)
        if (libraryState.tracks.isEmpty() && !libraryState.loading && libraryState.error == null) {
            Text("No audio files found yet.", color = NuvxColors.Secondary, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        } else LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            itemsIndexed(libraryState.tracks, key = { _, track -> track.videoId }) { index, track ->
                TrackRow(track, active = playerState.current?.videoId == track.videoId) { player.playQueue(libraryState.tracks, index) }
            }
        }
    }
}
