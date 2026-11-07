package com.bxane.nuvx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import com.bxane.nuvx.ui.ArtistPage
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real artist composable, fake catalog/player; no stream or external service is used. */
@OptIn(ExperimentalComposeUiApi::class)
class ArtistVideoBrowserTest {
    @Test fun searchAndSortKeepDisplayedQueueOrderAndPaginationRetryUsable() = checkBrowser(560, 1100, 1f)

    @Test fun narrowLargeTextCatalogKeepsTheSameSearchSortAndRetryActions() = checkBrowser(360, 900, 1.3f)

    private fun checkBrowser(width: Int, height: Int, fontScale: Float) {
        SwingUtilities.invokeAndWait {
            val zulu = Track("aaaaaaaaaaa", "Zulu session", listOf("Primary artist"), durationMs = 90_000, mediaKind = MediaKind.VIDEO)
            val alpha = Track("bbbbbbbbbbb", "Alpha concert", listOf("Guest artist"), durationMs = 30_000, mediaKind = MediaKind.VIDEO)
            val next = Track("ccccccccccc", "New clip", listOf("Primary artist"), durationMs = 60_000, mediaKind = MediaKind.VIDEO)
            val shelf = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, listOf(zulu, alpha), moreBrowseId = "more-videos")
            val artist = Artist("UC1", "Primary artist", null, emptyList(), emptyList(), listOf(shelf))
            var pageCalls = 0
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = error("catalog browsing must not resolve media")
                override suspend fun artist(browseId: String) = artist
                override suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf {
                    pageCalls++
                    assertEquals("more-videos", shelf.moreBrowseId)
                    if (pageCalls == 1) throw IllegalStateException("Synthetic retryable catalog failure")
                    return shelf.copy(items = listOf(next), moreBrowseId = null)
                }
            }
            var played = emptyList<Track>()
            var playedIndex = -1
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState())
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
                    played = tracks
                    playedIndex = startIndex
                }
                override fun enqueue(tracks: List<Track>) = Unit
                override fun togglePlay() = Unit
                override fun seekTo(positionMs: Long) = Unit
                override fun next() = Unit
                override fun previous() = Unit
                override fun skipTo(index: Int) = Unit
            }
            val router = BackendRouter(mapOf(backend.id to backend))
            val scene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
                MaterialTheme {
                    ArtistPage(router, player, player.state.value,
                        Collection(Collection.Kind.ARTIST, "UC1", "Primary artist", null, null), {}, reduceMotion = true)
                }
            }
            var frame = 0L
            fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            fun description(label: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
            fun text(label: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
            fun click(node: SemanticsNode?) {
                assertEquals(true, assertNotNull(node, "missing catalog action").config[SemanticsActions.OnClick].action?.invoke())
                render()
            }
            fun query(value: String) {
                val field = assertNotNull(description("Search loaded artist videos"), "missing artist video search")
                assertEquals(true, field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)))
                render()
            }
            fun playCard() = click(nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play video" })
            try {
                render()
                val search = assertNotNull(description("Search loaded artist videos"))
                assertTrue(search.boundsInRoot.width > 0f && search.boundsInRoot.height > 0f, "search must be visible at $width fontScale=$fontScale")
                query(" GUEST ")
                playCard()
                assertEquals(listOf(alpha), played)
                assertEquals(0, playedIndex)
                click(description("Clear video search"))
                click(description("Sort artist videos"))
                click(text("Title"))
                playCard()
                assertEquals(listOf(alpha, zulu), played, "queue order must match the displayed sort")
                query("new clip")
                assertNotNull(text("No loaded videos match this search."))
                click(text("Load more videos"))
                assertEquals(1, pageCalls)
                click(text("Retry loading videos"))
                assertEquals(2, pageCalls)
                playCard()
                assertEquals(listOf(next), played, "new pages must participate in the active search")
                assertEquals(0, playedIndex)
                click(description("Clear video search"))
                playCard()
                assertEquals(listOf(alpha, next, zulu), played, "clearing search restores all loaded videos without losing the sort")
                assertEquals(2, pageCalls, "search/sort must not refetch the catalog")
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
