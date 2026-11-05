package com.bxane.nuvx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
internal fun VideoSurface(player: AndroidPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player.controller
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier,
        update = { view -> if (view.player !== player.controller) view.player = player.controller },
        onRelease = { view -> view.player = null },
    )
}
