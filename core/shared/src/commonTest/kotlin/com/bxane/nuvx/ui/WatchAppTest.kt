package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchAppTest {
    @Test
    fun watchSubpagesUseTheirGlyphStudioNavigationSymbols() {
        assertEquals("magnifyingglass", watchPageGlyph("search"))
        assertEquals("list.bullet", watchPageGlyph("queue"))
        assertEquals("waveform", watchPageGlyph("engine"))
        assertNull(watchPageGlyph("home"))
    }
}
