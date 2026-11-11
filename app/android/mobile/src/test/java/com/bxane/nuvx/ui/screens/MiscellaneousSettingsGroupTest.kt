package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.PodcastsSymbol
import org.junit.Assert.assertSame
import org.junit.Test

class MiscellaneousSettingsGroupTest {
    @Test
    fun hidePodcastsTabUsesNavigationGlyph() {
        assertSame(PodcastsSymbol, hidePodcastsTabSymbol())
    }
}
