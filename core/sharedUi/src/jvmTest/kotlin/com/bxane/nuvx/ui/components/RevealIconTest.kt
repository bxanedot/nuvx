package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class RevealIconTest {
    @Test fun settingsGlyphsFindTheirSymbols() {
        assertEquals("magnifyingglass", SfIcons.Search.sfSymbol()?.name)
        assertEquals("car.fill", SfIcons.Car.sfSymbol()?.name)
        assertEquals("square.grid.2x2.fill", SfIcons.TabExplore.sfSymbol()?.name)
        assertEquals("arrow.down.circle", SfIcons.Download.sfSymbol()?.name)
        assertEquals("folder", SfIcons.Folder.sfSymbol()?.name)
    }
}
