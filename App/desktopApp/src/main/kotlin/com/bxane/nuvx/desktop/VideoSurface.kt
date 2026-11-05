package com.bxane.nuvx.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun DesktopVideoSurface(player: DesktopPlayer, modifier: Modifier = Modifier) {
    DisposableEffect(player) {
        player.setVideoVisible(true)
        onDispose { player.setVideoVisible(false) }
    }
    val frame by player.frame.collectAsState()
    frame?.let { Image(it, contentDescription = "Music video", modifier = modifier, contentScale = ContentScale.Fit) }
}
