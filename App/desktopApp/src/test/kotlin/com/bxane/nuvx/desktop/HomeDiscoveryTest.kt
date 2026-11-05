package com.bxane.nuvx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class)
class HomeDiscoveryTest {
    @Test fun emptyHistoryOffersExploreSearchAndCatalogBackedMoodActions() {
        SwingUtilities.invokeAndWait {
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = null
            }
            val player = object : NuvxPlayer {
                override val state = MutableStateFlow(PlayerState())
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
            val router = BackendRouter(mapOf(backend.id to backend))
            val history = History(store)
            var explored = false
            var searched = false
            var moodQuery: String? = null
            val scene = ImageComposeScene(420, 900) {
                MaterialTheme {
                    HomeScreen(history, router, player, {}, {}, { explored = true }, { searched = true }, { moodQuery = it })
                }
            }
            fun render() { repeat(4) { scene.render(it * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            fun click(node: SemanticsNode?) {
                assertEquals(true, assertNotNull(node, "missing Home action").config[SemanticsActions.OnClick].action?.invoke())
                render()
            }
            try {
                render()
                click(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Explore") == true })
                assertEquals(true, explored)
                click(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Search") == true })
                assertEquals(true, searched)
                click(nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label?.contains("Search Chill") == true })
                assertEquals("chill music", moodQuery)
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
