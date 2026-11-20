package com.licht.nuvx

import com.composables.sfsymbols.SfSymbol
import com.licht.nuvx.ui.icons.NuvxGlyph
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvxGlyphTest {

    @Test
    fun previousTrackUsesSupportedSfStudioSkipSymbol() {
        assertEquals("backward.end.fill", NuvxGlyph.Previous.sfName)
        assertNotNull(SfSymbol.named(NuvxGlyph.Previous.sfName))
    }

    @Test
    fun everyNuvxGlyphExistsInSfStudio() {
        NuvxGlyph.entries.forEach { glyph -> assertNotNull("${glyph.name} -> ${glyph.sfName}", SfSymbol[glyph.sfName]) }
    }
}
