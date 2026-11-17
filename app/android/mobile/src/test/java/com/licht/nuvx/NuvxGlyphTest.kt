package com.licht.nuvx

import com.composables.sfsymbols.SfSymbol
import com.licht.nuvx.ui.icons.NuvxGlyph
import org.junit.Assert.assertNotNull
import org.junit.Test

class NuvxGlyphTest {

    @Test
    fun everyNuvxGlyphExistsInSfStudio() {
        NuvxGlyph.entries.forEach { glyph -> assertNotNull("${glyph.name} -> ${glyph.sfName}", SfSymbol[glyph.sfName]) }
    }
}
