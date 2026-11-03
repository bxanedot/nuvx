package com.bxane.nuvx

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * A community canvas: the cover's looping video, silent and cropped to fill. It has its own player with the audio track
 * disabled and no audio focus, so it never touches music playback. A TextureView stays transparent until the first frame,
 * so the still cover underneath shows while it loads.
 */
@Composable
fun CanvasVideo(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val video = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(video) { onDispose { video.release() } }
    key(video) {
        AndroidView({ ctx ->
            TextureView(ctx).also { view ->
                video.setVideoTextureView(view)
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> view.cropTo(video.videoSize) }
                video.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) = view.cropTo(size)
                })
            }
        }, modifier)
    }
}

/** Scale the video like ContentScale.Crop: fill the view, keep the aspect ratio, cut the overflow evenly. */
private fun TextureView.cropTo(size: VideoSize) {
    if (size.width == 0 || size.height == 0 || width == 0 || height == 0) return
    val videoW = size.width * size.pixelWidthHeightRatio
    val scale = maxOf(width / videoW, height / size.height.toFloat())
    setTransform(Matrix().apply { setScale(videoW * scale / width, size.height * scale / height, width / 2f, height / 2f) })
}
