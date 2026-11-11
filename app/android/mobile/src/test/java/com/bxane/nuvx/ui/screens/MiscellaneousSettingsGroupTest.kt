package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.PodcastsSymbol
import com.bxane.nuvx.ui.components.TAB_SYMBOL_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MiscellaneousSettingsGroupTest {
    @Test
    fun hidePodcastsTabUsesNavigationGlyph() {
        assertSame(PodcastsSymbol, hidePodcastsTabSymbol())
    }

    @Test
    fun hidePodcastsTabGlyphMatchesNavigationSize() {
        assertEquals(TAB_SYMBOL_SIZE, hidePodcastsTabGlyphSize())
    }
}
