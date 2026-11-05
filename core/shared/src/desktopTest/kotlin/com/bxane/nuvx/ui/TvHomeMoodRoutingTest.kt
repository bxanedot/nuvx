package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TvHomeMoodRoutingTest {
    @Test fun homeMoodOpensSearchWithItsQueryOnTv() {
        SwingUtilities.invokeAndWait {
            val searches = mutableListOf<Pair<String, SearchFilter>>()
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                    searches += query to filter
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
            val router = BackendRouter(mapOf(backend.id to backend))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val history = History(store)
            val scene = ImageComposeScene(1280, 900) {
                MaterialTheme {
                    TvShell(
                        router = router, history = history, state = PlayerState(), player = player,
                        lyrics = LyricsChain(emptyList()), localLibrary = null, reduceMotion = true,
                        onBackHandler = null, radio = RadioStation(router, player, scope),
                        lyricSettings = LyricsProviderSettings(emptyList(), preferredSource = null),
                        onLyricsChange = {}, lyricsOffsetMs = 0, onLyricsOffsetChange = {},
                        pageContent = {},
                    )
                }
            }
            var frame = 0L
            fun render() { repeat(4) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            try {
                render()
                val mood = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label?.contains("Search Chill") == true
                }, "missing Home mood tile on TV")
                assertEquals(true, mood.config[SemanticsActions.OnClick].action?.invoke())
                repeat(8) { render() }
                assertTrue(searches.any { (query, filter) -> query == "chill music" && filter == SearchFilter.ALL },
                    "TV Home mood selection should open Search with its original query: $searches")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
