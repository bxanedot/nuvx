package com.bxane.nuvx.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSidebarLayoutTest {
    @Test
    fun settingsStaysVisibleWhenNavigationNeedsToScroll() {
        EventQueue.invokeAndWait {
            val scene = ImageComposeScene(200, 260, density = Density(1f)) {
                DesktopSidebar(
                    width = 200.dp,
                    compact = false,
                    selectedTab = 0,
                    searchSelected = false,
                    selectedPage = null,
                    settingsOpen = false,
                    reduceMotion = true,
                    magneticNavigation = false,
                    onHome = {},
                    onSearch = {},
                    onSubmitSearch = {},
                    searchQuery = null,
                    onExplore = {},
                    onLibrary = {},
                    onHistory = {},
                    onLocal = null,
                    onSettings = {},
                )
            }
            try {
                scene.render(0L).use { }
                val settings = scene.semanticsOwners
                    .flatMap { descendants(it.rootSemanticsNode) }
                    .firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings") == true }

                assertNotNull(settings, "Settings must remain available in a short window")
                assertTrue(settings.boundsInRoot.top >= 0f, "Settings must not render above the sidebar")
                assertTrue(settings.boundsInRoot.bottom <= 260f, "Settings must remain inside the visible sidebar")
            } finally {
                scene.close()
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
