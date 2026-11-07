package com.bxane.nuvx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.NowPlayingSheet
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exercises composition disposal with a fake Canvas surface; no video decoding or external traffic. */
@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingCanvasLifecycleTest {
    @Test fun reducedMotionAndVideoModeDisposeAnAlreadyLoadedCanvas() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/canvas/aaaaaaaaaaa.mp4") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val http = nuvxHttpClient()
        try {
            val canvas = CommunityCanvas(http, "http://127.0.0.1:${server.address.port}/canvas/")
            runBlocking { assertNotNull(canvas.find("aaaaaaaaaaa")) }
            for (fullBleed in listOf(true, false)) {
                SwingUtilities.invokeAndWait { checkLifecycle(canvas, fullBleed) }
            }
        } finally {
            http.close()
            server.stop(0)
        }
    }

    private fun checkLifecycle(canvas: CommunityCanvas, fullBleed: Boolean) {
        val song = Track("aaaaaaaaaaa", "Canvas lifecycle verification", listOf("Test artist"),
            artworkUrl = "file:///missing-layout-artwork.png", durationMs = 180_000)
        val playback = mutableStateOf(PlayerState(listOf(song), 0, durationMs = 180_000))
        val reduceMotion = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var activeSurfaces = 0
        val canvasSurface: @Composable (String, Modifier) -> Unit = { url, _ ->
            DisposableEffect(url) {
                activeSurfaces++
                onDispose { activeSurfaces-- }
            }
        }
        val player = object : NuvxPlayer {
            override val state = MutableStateFlow(playback.value)
            override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) = Unit
            override fun enqueue(tracks: List<Track>) = Unit
            override fun togglePlay() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun next() = Unit
            override fun previous() = Unit
            override fun skipTo(index: Int) = Unit
        }
        val store = object : KeyValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val router = BackendRouter(emptyMap())
        val radio = RadioStation(router, player, scope)
        val history = History(store)
        val lyrics = LyricsChain(emptyList())
        val scene = ImageComposeScene(360, 780) {
            MaterialTheme {
                NowPlayingSheet(player, playback.value, lyrics, history, router, reduceMotion.value, fullBleed = fullBleed,
                    lyricSettings = LyricsProviderSettings(emptyList(), preferredSource = null), onLyricSettingsChange = {},
                    lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                    canvas = if (enabled.value) canvas to canvasSurface else null,
                    radio = radio, onClose = {})
            }
        }
        var frame = 0L
        fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
        try {
            render()
            assertEquals(1, activeSurfaces, "the song's Canvas should be mounted")
            reduceMotion.value = true
            render()
            assertEquals(0, activeSurfaces, "enabling reduced motion must dispose an already loaded Canvas")
            reduceMotion.value = false
            render()
            assertEquals(1, activeSurfaces, "motion may resume when allowed")
            playback.value = playback.value.copy(queue = listOf(song.copy(mediaKind = MediaKind.VIDEO)))
            render()
            assertEquals(0, activeSurfaces, "video mode must not keep a Canvas decoder mounted")
            playback.value = playback.value.copy(queue = listOf(song))
            render()
            assertEquals(1, activeSurfaces)
            enabled.value = false
            render()
            assertEquals(0, activeSurfaces, "disabling community Canvas must dispose the surface")
            enabled.value = true
            render()
            assertEquals(1, activeSurfaces)
        } finally {
            scene.close()
            scope.cancel()
        }
        assertEquals(0, activeSurfaces, "closing the sheet must dispose its Canvas")
    }
}
