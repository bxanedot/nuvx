package com.bxane.nuvx.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import com.bxane.nuvx.core.canvasFile
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable
internal fun CanvasVideo(url: String, modifier: Modifier = Modifier) {
    // Downloaded once and looped from disk (see canvasFile); the still cover shows until it is ready.
    val local by produceState<String?>(null, url) {
        value = withContext(Dispatchers.IO) { runCatching { canvasFile(url, File(System.getProperty("java.io.tmpdir"), "nuvx-canvas")).absolutePath }.getOrNull() }
    }
    local?.let { LoopingCanvas(it, modifier) }
}

@Composable
private fun LoopingCanvas(url: String, modifier: Modifier) {
    val video = remember(url) { runCatching { CanvasRenderer(url) }.getOrNull() }
    DisposableEffect(video) { onDispose { video?.close() } }
    video?.frame?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop) }
}

private class CanvasRenderer(url: String) : AutoCloseable {
    private val factory = MediaPlayerFactory("--no-audio", "--quiet")
    private val player: EmbeddedMediaPlayer
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    init {
        try {
            player = factory.mediaPlayers().newEmbeddedMediaPlayer()
            val surface = factory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)
                override fun newFormatSize(width: Int, height: Int, visibleWidth: Int, visibleHeight: Int) = Unit
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
            }, object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) = Unit
                override fun unlock(mediaPlayer: MediaPlayer) = Unit
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
                    runCatching { rv32Image(nativeBuffers[0], format.width, format.height, format.pitches[0]).toComposeImageBitmap() }
                        .onSuccess { frame = it }
                }
            }, true)
            player.videoSurface().set(surface)
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) { runCatching { mediaPlayer.media().play(url, ":no-audio") } }
            })
            check(player.media().play(url, ":no-audio"))
        } catch (e: Throwable) {
            runCatching { factory.release() }
            throw e
        }
    }

    override fun close() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}

internal fun rv32Image(buffer: ByteBuffer, width: Int, height: Int, pitch: Int): BufferedImage {
    // The last row needs only its own pixels, not a full pitch.
    require(width > 0 && height > 0 && pitch >= width * 4 && pitch % 4 == 0 && buffer.capacity() >= pitch * (height - 1) + width * 4)
    // RV32 is B,G,R,X per pixel: read little-endian that is 0xXXRRGGBB, which TYPE_INT_RGB takes as is, so rows copy in bulk.
    val pixels = buffer.duplicate().apply { clear() }.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
        val out = (image.raster.dataBuffer as DataBufferInt).data
        for (y in 0 until height) pixels.position(y * pitch / 4).get(out, y * width, width)
    }
}
