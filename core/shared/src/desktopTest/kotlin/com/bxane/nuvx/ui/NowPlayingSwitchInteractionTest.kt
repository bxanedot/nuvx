package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingSwitchInteractionTest {
    @Test fun unavailableVideoIsHiddenAndSongAudioKeepsTheCurrentPosition() = checkSwitch(400, 900, 1f)
    @Test fun narrowPhoneAtDoubleFontKeepsSongAudioReachable() = checkSwitch(320, 640, 2f)

    private fun checkSwitch(width: Int, height: Int, fontScale: Float) {
        SwingUtilities.invokeAndWait {
            val song = Track("aaaaaaaaaaa", "Signal", listOf("Nova"), durationMs = 180_000,
                pairedVideoId = "bbbbbbbbbbb")
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState(listOf(song), 0, positionMs = 12_345))
                val replacements = mutableListOf<Pair<Track, Long>>()
                override fun replaceCurrent(track: Track, positionMs: Long) { replacements += track to positionMs }
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) = Unit
                override fun enqueue(tracks: List<Track>) = Unit
                override fun togglePlay() = Unit
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
                MaterialTheme {
                    NowPlayingSheet(player, state, lyrics, history, router, reduceMotion = true,
                        lyricSettings = LyricsProviderSettings(emptyList(), preferredSource = null),
                        onLyricSettingsChange = {}, lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                        androidUi = true, videoSurface = {}, radio = radio, onClose = {})
                }
            }
            var frame = 0L
            fun render() { repeat(8) { scene.render(frame++ * 16_000_000L).close() } }
            fun controls(label: String) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                .filter { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            try {
                render()
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
