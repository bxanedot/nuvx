package com.bxane.nuvx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import com.bxane.nuvx.ui.ArtistPage
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Uses a fake catalog and player: activating a menu must not also activate the video card. */
@OptIn(ExperimentalComposeUiApi::class)
class ArtistVideoActionsTest {
    @Test fun artistVideosUseTheExistingQueueActionsWithoutStartingPlayback() {
        SwingUtilities.invokeAndWait {
            val video = Track("aaaaaaaaaaa", "Test artist video", listOf("Test artist"), artistId = "UC1", mediaKind = MediaKind.VIDEO)
            val shelf = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, listOf(video))
            val artist = Artist("UC1", "Test artist", null, emptyList(), emptyList(), listOf(shelf))
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = null
                override suspend fun artist(browseId: String) = artist
            }
            var queued = emptyList<Track>()
            var next = emptyList<Track>()
            var played = emptyList<Track>()
            var playedIndex = -1
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState())
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
                    played = tracks
                    playedIndex = startIndex
                }
                override fun enqueue(tracks: List<Track>) { queued = tracks }
                override fun playNext(tracks: List<Track>) { next = tracks }
                override fun togglePlay() = Unit
                override fun seekTo(positionMs: Long) = Unit
                override fun next() = Unit
                override fun previous() = Unit
                override fun skipTo(index: Int) = Unit
            }
            val router = BackendRouter(mapOf(backend.id to backend))
            val scene = ImageComposeScene(560, 900) {
                MaterialTheme {
                    ArtistPage(router, player, player.state.value,
                        Collection(Collection.Kind.ARTIST, "UC1", "Test artist", null, null), {}, reduceMotion = true)
                }
            }
            var frame = 0L
            fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            fun click(node: SemanticsNode?) {
                val target = assertNotNull(node, "missing artist video action")
                assertEquals(true, target.config[SemanticsActions.OnClick].action?.invoke())
                render()
            }
            fun openMenu() = click(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("More options for ${video.title}") == true
            })
            fun menuItem(label: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            try {
                render()
                openMenu()
                click(menuItem("Add to queue"))
                assertEquals(listOf(video), queued)
                assertEquals(emptyList(), played, "opening/using the menu must not play the card")
                openMenu()
                click(menuItem("Play next"))
                assertEquals(listOf(video), next)
                assertEquals(emptyList(), played)
                click(nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play video" })
                assertEquals(shelf.items, played, "explicit playback retains the artist shelf context")
                assertEquals(0, playedIndex)
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
