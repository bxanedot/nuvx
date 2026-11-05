package com.bxane.nuvx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import com.bxane.nuvx.ui.SearchScreen
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SearchScreenStateTest {
    @Test fun emptyResultsAndProviderFailureHaveDistinctFeedback() {
        assertSearchMessage(fail = false, expected = "Nothing found for \"Travis Barker remix\".")
        assertSearchMessage(fail = true, expected = "Search isn't available right now.")
    }

    private fun assertSearchMessage(fail: Boolean, expected: String) {
        SwingUtilities.invokeAndWait {
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                    if (fail) throw IllegalStateException("offline test")
                    return SearchResults(emptyList(), emptyList())
                }
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
            val scene = ImageComposeScene(480, 840) {
                MaterialTheme {
                    SearchScreen(BackendRouter(mapOf(backend.id to backend)), player, History(store), "Travis Barker remix", {}, {})
                }
            }
            try {
                repeat(6) { scene.render(it * 16_000_000L).close() }
                val visibleText = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .joinToString("\n") { it.text }
                assertTrue(expected in visibleText, "expected '$expected' in search UI: $visibleText")
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
