package com.bxane.nuvx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.bxane.nuvx.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class HomeRadioSeedTest {
    @Test fun localOnlyHistoryMakesNoRadioRequest() = checkSeed(false)
    @Test fun recentLocalTrackKeepsLastOnlineSeed() = checkSeed(true)

    private fun checkSeed(withOnline: Boolean) {
        SwingUtilities.invokeAndWait {
            val requests = mutableListOf<String>()
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun stream(videoId: String): AudioStream? = null
                override suspend fun radio(seedVideoId: String, continuation: String?): RadioPage {
                    requests += seedVideoId
                    return RadioPage(emptyList(), null)
                }
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
            val history = History(store)
            if (withOnline) history.played(Track("online-seed", "Online song"))
            history.played(Track("local:device-track", "Local song", localUri = "content://media/audio/1"))
            val scene = ImageComposeScene(500, 850) {
                MaterialTheme {
                    HomeScreen(history, BackendRouter(mapOf(backend.id to backend)), player,
                        onTogether = {}, onAccount = {}, onExplore = {}, onSearch = {}, onMood = {}, androidUi = true)
                }
            }
            try {
                repeat(8) { scene.render(it * 16_000_000L).close() }
                assertEquals(if (withOnline) listOf("online-seed") else emptyList(), requests)
            } finally { scene.close() }
        }
    }
}
