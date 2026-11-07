package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ArtistVideoBrowserInteractionTest {
    @Test fun categorySearchSortAndClearPlayTheDisplayedQueueAtTheClickedIndex() = checkBrowser(700, 1100, 1f)
    @Test fun narrowBrowserKeepsTheSameFilteredQueueAndNonzeroSelection() = checkBrowser(360, 900, 1.3f)

    private fun checkBrowser(width: Int, height: Int, fontScale: Float) {
        SwingUtilities.invokeAndWait {
            val context = "${width}x$height fontScale=$fontScale"
            val music = Track("aaaaaaaaaaa", "Music guest clip", listOf("Guest artist"), durationMs = 20_000, mediaKind = MediaKind.VIDEO)
            val zulu = Track("bbbbbbbbbbb", "Zulu live", listOf("Guest artist"), durationMs = 90_000, mediaKind = MediaKind.VIDEO)
            val bravo = Track("ccccccccccc", "Bravo live", listOf("House artist"), durationMs = 10_000, mediaKind = MediaKind.VIDEO)
            val alpha = Track("ddddddddddd", "Alpha live", listOf("Guest artist"), durationMs = 120_000, mediaKind = MediaKind.VIDEO)
            val catalog = listOf(music, zulu, bravo, alpha)
            val artist = Artist("UC-local", "Interaction artist", null, emptyList(), emptyList(), listOf(
                ArtistVideoShelf("Official clips", ArtistVideoCategory.MUSIC_VIDEO, listOf(music)),
                ArtistVideoShelf("Concert recordings", ArtistVideoCategory.LIVE_PERFORMANCE, listOf(zulu, bravo, alpha),
                    moreBrowseId = "unloaded-live-page"),
            ))
            val backendCalls = mutableListOf<String>()
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun artist(browseId: String): Artist {
                    backendCalls += "artist:$browseId"
                    return artist
                }
                override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                    backendCalls += "search"
                    error("loaded catalog filtering must not perform backend search")
                }
                override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
                    backendCalls += "radio"
                    error("catalog interaction must not request radio")
                }
                override suspend fun stream(videoId: String): AudioStream? {
                    backendCalls += "stream"
                    error("the recording player must not resolve media")
                }
                override suspend fun artistVideoPage(browseId: String, artistName: String, shelf: ArtistVideoShelf): ArtistVideoShelf {
                    backendCalls += "page"
                    error("filtering must not silently fetch unloaded pages")
                }
            }
            val requests = mutableListOf<Pair<List<Track>, Int>>()
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState())
                override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
                    assertEquals(0L, initialPositionMs, context)
                    assertTrue(autoplay, context)
                    requests += tracks.toList() to startIndex
                }
                override fun enqueue(tracks: List<Track>) = error("expected a video-card play action")
                override fun togglePlay() = error("expected a video-card play action")
                override fun seekTo(positionMs: Long) = error("expected a video-card play action")
                override fun next() = error("expected a video-card play action")
                override fun previous() = error("expected a video-card play action")
                override fun skipTo(index: Int) = error("expected a video-card play action")
            }
            val router = BackendRouter(mapOf(backend.id to backend))
            val scene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
                MaterialTheme {
                    ArtistPage(router, player, player.state.value,
                        Collection(Collection.Kind.ARTIST, artist.browseId, artist.name, null, null),
                        onOpen = { error("video browsing must not open another collection") }, reduceMotion = true)
                }
            }
            var frame = 0L
            fun render() { repeat(12) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            fun text(label: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
            fun description(label: String) = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
            fun click(node: SemanticsNode?) {
                val action = assertNotNull(node, "$context missing screen action")
                assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke(), context)
                render()
            }
            fun category(label: String) = nodes().single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
            }
            fun query(value: String) {
                val field = assertNotNull(description("Search loaded artist videos"), context)
                assertEquals(true, field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)), context)
                render()
            }
            fun sort(label: String) {
                click(description("Sort artist videos"))
                click(text(label))
                assertNotNull(text("Sort: $label"), context)
            }
            fun cards() = nodes().filter {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play video" &&
                    it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
            }.sortedBy { it.boundsInRoot.left }
            fun cardTrack(node: SemanticsNode): Track {
                val labels = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                return catalog.single { it.title in labels }
            }
            fun scrollToCard(index: Int) {
                val row = nodes().single {
                    it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null &&
                        it.config.getOrNull(SemanticsActions.ScrollToIndex) != null
                }
                assertEquals(true, row.config[SemanticsActions.ScrollToIndex].action?.invoke(index), context)
                render()
            }
            fun checkDisplayedQueueAndPlay(expected: List<Track>, selectedIndex: Int) {
                val observed = linkedSetOf<Track>()
                for (index in expected.indices) {
                    scrollToCard(index)
                    val displayed = cards().map(::cardTrack)
                    assertTrue(displayed.isNotEmpty(), "$context has no visible video cards")
                    assertEquals(expected.filter { it in displayed }, displayed, "$context displayed order/category differs")
                    observed += displayed
                }
                assertEquals(expected, observed.toList(), "$context must expose every expected card in order")
                scrollToCard(selectedIndex)
                val card = cards().single { cardTrack(it) == expected[selectedIndex] }
                assertEquals(Role.Button, card.config.getOrNull(SemanticsProperties.Role), context)
                assertTrue(card.boundsInRoot.width >= 199f, "$context selected card must be fully visible horizontally")
                val count = requests.size
                click(card)
                assertEquals(count + 1, requests.size, "$context one card click must dispatch once")
                assertEquals(expected to selectedIndex, requests.last(), "$context played queue/index must match the displayed cards")
                println("$context displayed=${observed.map { it.videoId }} played=${requests.last().first.map { it.videoId }} selected=$selectedIndex")
            }

            try {
                render()
                assertEquals(true, category("All").config.getOrNull(SemanticsProperties.Selected), context)
                assertNotNull(text("4 of 4 loaded videos"), context)
                click(category("Live"))
                assertEquals(true, category("Live").config.getOrNull(SemanticsProperties.Selected), context)
                assertEquals(false, category("All").config.getOrNull(SemanticsProperties.Selected), context)
                assertNotNull(text("3 of 4 loaded videos"), context)
                query("  gUeSt  ")
                assertNotNull(text("2 of 4 loaded videos"), context)
                sort("Title")
                checkDisplayedQueueAndPlay(listOf(alpha, zulu), 1)
                sort("Duration")
                checkDisplayedQueueAndPlay(listOf(zulu, alpha), 1)

                query("unloaded encore")
                assertNotNull(text("No loaded videos match this search."), context)
                assertNotNull(text("0 of 4 loaded videos"), context)
                assertNotNull(text("Load more videos"), "$context must not imply the unloaded page was searched")
                assertTrue(cards().isEmpty(), "$context an empty match must not leave a playable card")
                assertEquals(2, requests.size, "$context filtering must not dispatch playback")
                click(description("Clear video search"))
                assertEquals("", assertNotNull(description("Search loaded artist videos")).config[SemanticsProperties.EditableText].text, context)
                assertEquals(true, category("Live").config.getOrNull(SemanticsProperties.Selected), context)
                assertNotNull(text("Sort: Duration"), context)
                assertNotNull(text("3 of 4 loaded videos"), context)
                checkDisplayedQueueAndPlay(listOf(bravo, zulu, alpha), 1)
                click(category("Music videos"))
                assertNotNull(text("1 of 4 loaded videos"), context)
                checkDisplayedQueueAndPlay(listOf(music), 0)
                assertEquals(listOf("artist:UC-local"), backendCalls, "$context all filtering/sorting must stay local")
                assertEquals(4, requests.size, context)
                println("$context empty state, Clear, category isolation and local-only backend calls verified")
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
