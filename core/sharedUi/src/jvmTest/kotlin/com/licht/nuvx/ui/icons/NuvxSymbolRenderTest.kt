package com.licht.nuvx.ui.icons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Screenshot-level checks of the icon registry, rendered with Compose's desktop renderer. */
class NuvxSymbolRenderTest {

    private fun Bitmap.pixels() = IntArray(width * height) { getColor(it % width, it / width) }

    private fun still(glyph: NuvxGlyph): IntArray = ImageComposeScene(64, 64).use { scene ->
        scene.setContent { NuvxSymbol(glyph, null, Color.Black, 48.dp, reduce = true) }
        Bitmap.makeFromImage(scene.render()).pixels()
    }

    /** Shows Play, switches to Pause, and returns the frame 16 ms after the switch. */
    private fun frameAfterToggle(reduce: Boolean): IntArray = ImageComposeScene(64, 64).use { scene ->
        var glyph by mutableStateOf(NuvxGlyph.Play)
        scene.setContent { NuvxSymbol(glyph, null, Color.Black, 48.dp, reduce = reduce) }
        scene.render(0)
        glyph = NuvxGlyph.Pause
        scene.render(16_000_000)
        Bitmap.makeFromImage(scene.render(32_000_000)).pixels()
    }

    private fun differing(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }

    @Test
    fun everyGlyphDrawsVisibleInk() {
        NuvxGlyph.entries.forEach { glyph ->
            val ink = still(glyph).count { it ushr 24 > 0x40 }
            assertTrue(ink > 40, "${glyph.name} drew only $ink pixels")
        }
    }

    @Test
    fun playAndPauseLookDifferent() {
        assertTrue(differing(still(NuvxGlyph.Play), still(NuvxGlyph.Pause)) > 100)
    }

    @Test
    fun reducedMotionSwitchesAtOnce() {
        assertEquals(0, differing(frameAfterToggle(reduce = true), still(NuvxGlyph.Pause)))
    }

    @Test
    fun withMotionTheToggleIsAnimated() {
        assertTrue(differing(frameAfterToggle(reduce = false), still(NuvxGlyph.Pause)) > 0, "no in-between frame")
    }
}
