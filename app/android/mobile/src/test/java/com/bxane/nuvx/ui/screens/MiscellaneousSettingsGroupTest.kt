package com.bxane.nuvx.ui.screens

import com.composables.sfsymbols.SfSymbol
import org.junit.Assert.assertEquals
import org.junit.Test

class MiscellaneousSettingsGroupTest {
    @Test
    fun hidePodcastsTabUsesNavigationGlyph() {
        assertEquals(SfSymbol.named("microphone")!!.toImageVector(), hidePodcastsTabIcon())
    }
}
