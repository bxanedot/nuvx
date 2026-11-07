package com.bxane.nuvx.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.NowPlayingSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingTimeLayoutTest {
    @Test fun elapsedAndRemainingTimeStayVisibleOnPhoneScreens() {
        for ((width, height, fontScale) in listOf(Triple(360, 780, 1f), Triple(360, 740, 1.3f), Triple(412, 900, 1f))) {
            SwingUtilities.invokeAndWait { checkLayout(width, height, fontScale, fullBleed = true, mediaKind = MediaKind.SONG) }
        }
    }

    @Test fun normalArtworkKeepsItsTimeLabelsVisible() {
        for ((width, height, fontScale) in listOf(Triple(360, 780, 1f), Triple(360, 740, 1.3f), Triple(412, 900, 1f))) {
            SwingUtilities.invokeAndWait { checkLayout(width, height, fontScale, fullBleed = false, mediaKind = MediaKind.SONG) }
        }
    }

    @Test fun videoKeepsItsSurfaceAndTimeLabelsVisible() {
        for ((width, height, fontScale) in listOf(Triple(360, 780, 1f), Triple(360, 740, 1.3f), Triple(412, 900, 1f))) {
            SwingUtilities.invokeAndWait { checkLayout(width, height, fontScale, fullBleed = true, mediaKind = MediaKind.VIDEO) }
        }
    }

    @Test fun compactLandscapeWindowKeepsTimeLabelsVisibleOutsideArtwork() {
        SwingUtilities.invokeAndWait { checkLayout(550, 521, 1f, fullBleed = true, mediaKind = MediaKind.SONG) }
    }

    private fun checkLayout(width: Int, height: Int, fontScale: Float, fullBleed: Boolean, mediaKind: MediaKind) {
        val expectedFullCover = fullBleed && width < 600 && height >= width * 1.25f && mediaKind == MediaKind.SONG
        val layout = if (mediaKind == MediaKind.VIDEO) "video" else if (expectedFullCover) "full-cover" else "normal-art"
        val state = PlayerState(listOf(Track("aaaaaaaaaaa", "Player layout verification", listOf("Test artist"),
            artworkUrl = "file:///missing-layout-artwork.png", mediaKind = mediaKind)), 0, positionMs = 6_000, durationMs = 10_756_000)
        val player = object : NuvxPlayer {
            override val state = MutableStateFlow(state)
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
        val scene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(top = 32.dp, bottom = 24.dp)) {
                    NowPlayingSheet(player, state, lyrics, history, router, reduceMotion = true, fullBleed = fullBleed,
                        lyricSettings = LyricsProviderSettings(emptyList(), preferredSource = null),
                        onLyricSettingsChange = {}, lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                        videoSurface = { modifier -> Box(modifier.semantics { contentDescription = "Test video surface" }) },
                        radio = radio, onClose = {})
                }
            }
        }
        try {
            repeat(4) { frame ->
                scene.render(frame * 16_000_000L).use { image ->
                    if (frame == 3) {
                        val output = Path.of("build", "reports", "player-layout", "$layout-${width}x${height}-$fontScale.png")
                        Files.createDirectories(output.parent)
                        requireNotNull(image.encodeToData()).use { data -> Files.write(output, data.bytes) }
                    }
                }
            }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            val likeChip = nodes.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Like") == true }
            assertEquals(!expectedFullCover, likeChip, "$layout must exercise the intended artwork branch")
            val video = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Test video surface") == true }
            if (mediaKind == MediaKind.VIDEO) {
                assertNotNull(video, "the video surface must not be replaced by full-cover artwork")
                assertTrue(video.boundsInRoot.width > 0f && video.boundsInRoot.height > 0f, "the video surface must be visible")
            } else assertEquals(null, video)
            for (label in listOf("0:06", "-179:10")) {
                val node = assertNotNull(nodes.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }, "missing $label")
                val layouts = mutableListOf<TextLayoutResult>()
                assertEquals(true, node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts))
                val text = layouts.single()
                assertFalse(text.didOverflowHeight, "$label is clipped in $layout at ${width}x$height font scale $fontScale")
                assertTrue(node.boundsInRoot.height + 0.5f >= text.size.height,
                    "$label is clipped by its parent at ${width}x$height font scale $fontScale: visible=${node.boundsInRoot.height}, measured=${text.size.height}")
            }
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
