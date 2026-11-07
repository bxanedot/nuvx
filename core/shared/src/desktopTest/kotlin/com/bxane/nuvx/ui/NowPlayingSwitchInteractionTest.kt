package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import java.io.File
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingSwitchInteractionTest {
    @Test fun unavailableVideoIsHiddenAndSongAudioKeepsTheCurrentPosition() = checkSwitch(400, 900, 1f)
    @Test fun narrowPhoneAtDoubleFontKeepsSongAudioReachable() = checkSwitch(320, 640, 2f)

    @Test fun shortLandscapeKeepsSongAudioReachable() = checkSwitch(640, 360, 1f)

    @Test fun lightAppPaletteKeepsPlayVisibleOnTheDarkPlayer() = checkSwitch(400, 900, 1f, lightPalette = true)

    private fun checkSwitch(width: Int, height: Int, fontScale: Float, lightPalette: Boolean = false) {
        SwingUtilities.invokeAndWait {
            val cover = File("../assets/brand/mark/nuvx_mark_white.png")
            assertTrue(cover.isFile, "Owned test artwork must be inside the source checkout")
            val song = Track("aaaaaaaaaaa", "Signal", listOf("Nova"), durationMs = 180_000,
                pairedVideoId = "bbbbbbbbbbb", artworkUrl = cover.toURI().toString())
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState(listOf(song), 0, positionMs = 12_345))
                val replacements = mutableListOf<Pair<Track, Long>>()
                override fun replaceCurrent(track: Track, positionMs: Long) { replacements += track to positionMs }
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) = Unit
                override fun enqueue(tracks: List<Track>) = Unit
                var toggles = 0
                override fun togglePlay() { toggles++ }
                override fun seekTo(positionMs: Long) = Unit
                override fun next() = Unit
                override fun previous() = Unit
                override fun skipTo(index: Int) = Unit
            }
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter): SearchResults =
                    throw IllegalStateException("test provider unavailable")
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = null
            }
            val store = object : KeyValueStore {
                override fun get(key: String): String? = null
                override fun put(key: String, value: String?) = Unit
            }
            val router = BackendRouter(mapOf(backend.id to backend))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val radio = RadioStation(router, player, scope)
            val history = History(store)
            val lyrics = LyricsChain(emptyList())
            val scene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
                val state = player.state.collectAsState().value
                CompositionLocalProvider(LocalNuvxPalette provides if (lightPalette) NuvxPalette.Light else NuvxPalette.Dark) {
                MaterialTheme {
                    NowPlayingSheet(player, state, lyrics, history, router, reduceMotion = true,
                        lyricSettings = LyricsProviderSettings(emptyList(), preferredSource = null),
                        onLyricSettingsChange = {}, lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                        androidUi = true, videoSurface = {}, radio = radio, onClose = {})
                }
                }
            }
            var frame = 0L
            fun render() { repeat(8) { scene.render(frame++ * 16_000_000L).close() } }
            fun controls(label: String) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                .filter { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            try {
                render()
                val title = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Signal" } == true }
                val titleBounds = title.boundsInRoot
                assertTrue(titleBounds.height > 0 && titleBounds.top >= 0 && titleBounds.bottom <= height,
                    "Track title must remain visible: $titleBounds")
                val closeBounds = controls("Close player").single().boundsInRoot
                assertTrue(closeBounds.height > 0 && closeBounds.top >= 0 && closeBounds.bottom <= titleBounds.top,
                    "Header must remain above the track title: $closeBounds / $titleBounds")
                val images = File("build/reports/player-images").apply { mkdirs() }
                scene.render(frame++ * 16_000_000L).use { image ->
                    image.encodeToData()?.use { File(images, "$width-$height-$fontScale-$lightPalette.png").writeBytes(it.bytes) }
                    Bitmap().use { pixels ->
                        assertTrue(pixels.allocPixels(image.imageInfo))
                        assertTrue(image.readPixels(pixels))
                        fun brightPixels(bounds: androidx.compose.ui.geometry.Rect): Int {
                            var bright = 0
                            for (y in bounds.top.toInt().coerceAtLeast(0) until minOf(height, bounds.bottom.toInt())) {
                                for (x in bounds.left.toInt().coerceAtLeast(0) until minOf(width, bounds.right.toInt())) {
                                    val color = pixels.getColor(x, y)
                                    if ((color ushr 16 and 255) > 220 && (color ushr 8 and 255) > 220 && (color and 255) > 220) bright++
                                }
                            }
                            return bright
                        }
                        assertTrue(brightPixels(titleBounds) > 20, "Track title must not be painted over by artwork")
                        if (lightPalette) assertTrue(brightPixels(controls("Play").single().boundsInRoot) > 20,
                            "Play must remain bright on the dark player in light app mode")
                    }
                }
                val playBounds = controls("Play").single().boundsInRoot
                player.state.value = player.state.value.copy(buffering = true, isPlaying = true)
                render()
                val buffering = controls("Pause").single()
                assertEquals("Buffering", buffering.config[SemanticsProperties.StateDescription])
                assertEquals(playBounds, buffering.boundsInRoot, "Loading must not move or shrink the transport target")
                assertEquals(true, buffering.config[SemanticsActions.OnClick].action?.invoke())
                assertEquals(1, player.toggles, "Loading must preserve the pause action")
                player.state.value = player.state.value.copy(buffering = false, isPlaying = false)
                render()
                assertEquals(playBounds, controls("Play").single().boundsInRoot)
                assertTrue(controls("Watch video").isEmpty(), "Failed lookup must not expose an inert action")
                player.state.value = player.state.value.copy(queue = listOf(song.copy(mediaKind = MediaKind.VIDEO)))
                render()
                val action = controls("Song audio").single()
                val bounds = action.boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= width && bounds.bottom <= height, "Song audio must remain visible: $bounds" )
                assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke())
                assertEquals(listOf(song to 12_345L), player.replacements)
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
