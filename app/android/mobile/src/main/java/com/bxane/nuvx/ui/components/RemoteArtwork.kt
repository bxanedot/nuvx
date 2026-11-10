package com.bxane.nuvx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.remote.RemoteArtworkStore

@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val context = LocalContext.current
    val key = song?.videoId
    var resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key) {
        if (song != null && song.thumbnailUrl == null &&
            (song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:"))
        ) {
            resolved = RemoteArtworkStore.resolveArt(context, song)
        }
    }
    return resolved
}
