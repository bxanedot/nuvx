package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.PodcastsSymbol
import org.junit.Assert.assertEquals
import org.junit.Test

class MiscellaneousSettingsGroupTest {
    @Test
    fun hidePodcastsTabUsesNavigationGlyph() {
        assertEquals(PodcastsSymbol.toImageVector(), hidePodcastsTabIcon())
    }
}
