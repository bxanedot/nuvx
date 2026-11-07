package com.bxane.nuvx.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.NowPlayingSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Catalog matching only: no stream resolution, real lyrics, or native player is used. */
@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingVideoMatchTest {
    @Test fun reportedDurationRetriesMatchingAndPreservesAnAlignedPosition() = checkDuration(null)

    @Test fun zeroCatalogDurationUsesThePositivePlaybackDuration() = checkDuration(0)

    private fun checkDuration(catalogDurationMs: Long?) {
        SwingUtilities.invokeAndWait {
            val song = Track("aaaaaaaaaaa", "Test song", listOf("Test artist"), artistId = "UC1", durationMs = catalogDurationMs)
            val video = Track("bbbbbbbbbbb", "Test song (Official Music Video)", song.artists,
                artistId = song.artistId, durationMs = 180_000, mediaKind = MediaKind.VIDEO)
            val playback = mutableStateOf(PlayerState(listOf(song), 0, positionMs = 14_000))
            var searches = 0
            val lyricDurations = mutableListOf<Long?>()
            val provider = object : LyricsProvider {
                override val name = "Test source"
                override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
                    lyricDurations += track.durationMs
                    return null
                }
            }
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                    assertEquals(SearchFilter.VIDEOS, filter)
                    searches++
                    return SearchResults(listOf(video), emptyList())
                }
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = error("the matching test must not resolve a stream")
            }
            var replacement: Pair<Track, Long>? = null
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(playback.value)
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) = Unit
                override fun replaceCurrent(track: Track, positionMs: Long) { replacement = track to positionMs }
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
            val router = BackendRouter(mapOf(backend.id to backend))
            val radio = RadioStation(router, player, scope)
            val history = History(store)
            val library = LyricsChain(listOf(provider))
            val scene = ImageComposeScene(412, 900) {
                MaterialTheme {
                    NowPlayingSheet(player, playback.value, library, history, router, reduceMotion = true,
                        lyricSettings = LyricsProviderSettings(listOf(provider.name), preferredSource = provider.name),
                        onLyricSettingsChange = {}, lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                        videoSurface = { modifier -> Box(modifier) }, radio = radio, onClose = {})
                }
            }
            var frame = 0L
            fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
            fun watchButton() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Watch video") == true
            }
            try {
                render()
                assertEquals(null, watchButton(), "a durationless catalog result must not be presented as a verified match")
                assertEquals(0, searches, "wait for useful duration metadata before searching")
                playback.value = playback.value.copy(durationMs = 180_000)
                render()
                assertNotNull(watchButton(), "reported duration must trigger matching without reopening the sheet")
                assertEquals(1, searches)
                assertEquals(listOf<Long?>(180_000L), lyricDurations.toList(), "lyrics matching also needs the positive playback duration")
                playback.value = playback.value.copy(positionMs = 15_000)
                render()
                assertEquals(1, searches, "position ticks must not repeat the catalog request")
                val button = assertNotNull(watchButton())
                assertEquals(true, button.config[SemanticsActions.OnClick].action?.invoke())
                val (track, position) = assertNotNull(replacement)
                assertEquals(MediaKind.VIDEO, track.mediaKind)
                assertEquals(song.videoId, track.videoId, "the existing queue identity is preserved")
                assertEquals(video.videoId, track.pairedVideoId)
                assertEquals(15_000L, position, "aligned recordings use the latest playback position")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
