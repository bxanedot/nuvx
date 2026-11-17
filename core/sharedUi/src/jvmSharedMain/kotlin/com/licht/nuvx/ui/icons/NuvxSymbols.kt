package com.licht.nuvx.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.sfsymbols.SfSymbol
import com.composables.sfsymbols.SfSymbolIcon
import com.composables.sfsymbols.SfSymbolTransition
import com.composables.sfsymbols.SfWeight
import com.licht.nuvx.ui.components.REVEAL_SYMBOL_SCALE
import com.licht.nuvx.ui.player.PlayerSettings

/**
 * nuvx's animated symbols: one entry per meaning, drawn by GlyphStudio. Every Compose surface uses
 * these, so a meaning has the same symbol, weight and motion everywhere. Add an entry when a screen
 * needs a new animated state.
 */
enum class NuvxGlyph(val sfName: String) {
    Play("play.fill"),
    Pause("pause.fill"),
    ;

    val symbol: SfSymbol get() = requireNotNull(SfSymbol[sfName]) { "GlyphStudio has no symbol $sfName" }
}

/**
 * Draws [glyph]. When the glyph changes (play to pause), GlyphStudio's Replace transition morphs it.
 * Motion only happens on that change, never continuously, and not at all with "reduce animation" on.
 */
@Composable
fun NuvxSymbol(glyph: NuvxGlyph, contentDescription: String?, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val reduce by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        SfSymbolIcon(
            symbol = glyph.symbol,
            size = size * REVEAL_SYMBOL_SCALE,
            weight = SfWeight.Bold,
            tint = tint,
            transition = if (reduce) SfSymbolTransition.None else SfSymbolTransition.Replace,
            contentDescription = contentDescription,
        )
    }
}
