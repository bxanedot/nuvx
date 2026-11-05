package com.bxane.nuvx.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPanelSizingTest {
    @Test fun threeColumnLayoutStartsAtTheDocumentedMinimumWidth() {
        val sizing = desktopPanelSizing(1040.dp)

        assertNull(desktopPanelSizing(1039.dp).listeningPane)
        assertEquals(160.dp, sizing.sidebar)
        assertEquals(320.dp, sizing.listeningPane)
    }

    @Test fun veryWideWindowKeepsCompactSidePanels() {
        val sizing = desktopPanelSizing(1440.dp)

        assertEquals(200.dp, sizing.sidebar)
        assertEquals(320.dp, sizing.listeningPane)
    }

    @Test fun hideListeningPaneWhenMinimumRouteAndInspectorDoNotFit() {
        assertNull(desktopPanelSizing(943.dp).listeningPane)
    }

    @Test fun narrowWindowUsesCompactNavigationRail() {
        assertEquals(64.dp, desktopPanelSizing(650.dp).sidebar)
        assertEquals(144.dp, desktopPanelSizing(760.dp).sidebar)
    }
}
