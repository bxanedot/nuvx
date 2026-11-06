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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SettingsCategoryBackTest {
    @Test fun platformBackReturnsToCategoriesBeforeLeavingSettings() {
        SwingUtilities.invokeAndWait {
            val backend = object : MusicBackend {
                override val id = BackendId.INNERTUBEX
                override suspend fun search(query: String, filter: SearchFilter) = SearchResults(emptyList(), emptyList())
                override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
                override suspend fun stream(videoId: String): AudioStream? = null
            }
            val store = object : KeyValueStore {
                override fun get(key: String): String? = null
                override fun put(key: String, value: String?) = Unit
            }
            val router = BackendRouter(mapOf(backend.id to backend))
            var enabled = false
            var back: (() -> Unit)? = null
            val scene = ImageComposeScene(400, 900) {
                MaterialTheme {
                    SettingsPage(router, store, emptyList(), LyricsProviderSettings(emptyList(), preferredSource = null), {},
                        androidUi = true, onBackHandler = { active, action -> enabled = active; back = action })
                }
            }
            var frame = 0L
            fun render() { repeat(6) { scene.render(frame++ * 16_000_000L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            fun hasText(node: SemanticsNode, value: String) = descendants(node).any {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
            }
            try {
                render()
                assertFalse(enabled)
                val category = nodes().single { it.config.getOrNull(SemanticsActions.OnClick) != null && hasText(it, "Playback") }
                assertEquals(true, category.config[SemanticsActions.OnClick].action?.invoke())
                render()
                assertTrue(enabled, "Category navigation must intercept platform Back")
                back!!.invoke()
                render()
                assertFalse(enabled, "Category list must return Back handling to the parent")
                assertTrue(nodes().any { hasText(it, "Appearance") }, "Back must restore the category list")
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
