package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.bxane.nuvx.core.*
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class)
class ExploreRecommendationsTest {
    @Test fun recommendationsPlayTheDisplayedQueueWithoutSeedOrDuplicates() {
        SwingUtilities.invokeAndWait {
            val seed = Track("seed", "Previous selection")
            val first = Track("first", "First suggestion")
            val second = Track("second", "Second suggestion")
            val requests = mutableListOf<String>()
            var played: Pair<List<Track>, Int>? = null
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun stream(videoId: String): AudioStream? = null
                override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
                    requests += seedVideoId
                    return RadioPage(listOf(seed, first, first, second), null)
                }
            }
            val scene = ImageComposeScene(700, 1000) {
                MaterialTheme {
                    ExploreScreen(
                        onMood = {}, router = BackendRouter(mapOf(backend.id to backend)), androidUi = true,
                        recommendationSeed = seed,
                        onPlayRecommendations = { tracks, index -> played = tracks to index },
                        onAccount = {},
                    )
                }
            }
            try {
                repeat(12) { scene.render(it * 16_000_000L).close() }
                val card = assertNotNull(scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                    .firstOrNull { node ->
                        node.config.getOrNull(SemanticsActions.OnClick) != null &&
                            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == second.title } == true
                    })
                assertEquals(true, card.config[SemanticsActions.OnClick].action?.invoke())
                assertEquals(listOf("seed"), requests)
                assertEquals(listOf(first, second) to 1, played)
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)
}
