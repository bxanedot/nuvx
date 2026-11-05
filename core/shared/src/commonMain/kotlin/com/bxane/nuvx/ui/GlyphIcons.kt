package com.bxane.nuvx.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.sfsymbols.SfEffectRepeat
import com.composables.sfsymbols.SfRenderingMode
import com.composables.sfsymbols.SfSymbol
import com.composables.sfsymbols.SfSymbolEffect
import com.composables.sfsymbols.SfSymbolIcon
import com.composables.sfsymbols.SfSymbolTransition
import com.composables.sfsymbols.SfSymbols
import com.composables.sfsymbols.SfWeight
import com.composables.sfsymbols.monochrome.SFDotRadiowavesLeftAndRight

@Composable
internal fun NuvxGlyphIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = NuvxColors.Text,
    selected: Boolean = false,
    trigger: Any? = selected,
    animateOnTrigger: Boolean = false,
    motionEnabled: Boolean = true,
) {
    val symbol = remember(name) { SfSymbol[name] }
    SfSymbolIcon(
        symbol = symbol,
        modifier = modifier,
        size = size,
        weight = if (selected) SfWeight.Semibold else SfWeight.Medium,
        mode = SfRenderingMode.Hierarchical,
        tint = tint,
        effect = if (motionEnabled && (selected || animateOnTrigger)) SfSymbolEffect.Bounce() else null,
        repeat = SfEffectRepeat.Once,
        trigger = trigger,
        transition = SfSymbolTransition.MagicReplace,
    )
}

@Composable
fun SFDotRadiowavesLeftAndRightIcon(modifier: Modifier = Modifier) =
    RadioSymbolIcon(modifier, 110.dp)

@Composable
internal fun RadioSymbolIcon(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Icon(
        imageVector = SfSymbols.Monochrome.SFDotRadiowavesLeftAndRight,
        contentDescription = "Radio",
        tint = Color(0xFFFEFFFE),
        modifier = modifier.then(Modifier.size(size)),
    )
}
