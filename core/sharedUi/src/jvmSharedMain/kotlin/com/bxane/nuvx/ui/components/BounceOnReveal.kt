package com.bxane.nuvx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.ui.player.PlayerSettings
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.composables.sfsymbols.SfSymbol
import com.composables.sfsymbols.SfSymbolEffect
import com.composables.sfsymbols.SfEffectScope
import com.composables.sfsymbols.SfSymbolIcon
import com.composables.sfsymbols.SfWeight

@Composable
fun Modifier.bounceOnReveal(): Modifier {
    val reduce by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    if (reduce) return this
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    return onVisibilityChanged(minFractionVisible = 1f) { visible ->
        if (visible) scope.launch {
            scale.animateTo(1.22f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMediumLow))
        }
    }.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

fun ImageVector.sfSymbol(): SfSymbol? = symbolsByGlyphName[name.removePrefix("Monochrome.").removeSuffix(".bold")]

private val symbolsByGlyphName: Map<String, SfSymbol> by lazy {
    SfSymbol.all.associateBy { symbol -> "SF" + symbol.name.split('.').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } }
}

private val RevealBounce = SfSymbolEffect.Bounce(scope = SfEffectScope.ByLayer)

@Composable
fun RevealIcon(icon: ImageVector, tint: Color, size: Dp) {
    val symbol = remember(icon) { icon.sfSymbol() }
    if (symbol == null) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size).bounceOnReveal())
        return
    }
    val reduce by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    var reveals by remember { mutableIntStateOf(0) }
    Box(
        Modifier.size(size).onVisibilityChanged(minFractionVisible = 1f) { visible -> if (visible && !reduce) reveals++ },
        contentAlignment = Alignment.Center,
    ) {
        SfSymbolIcon(
            symbol = symbol,
            size = size * REVEAL_SYMBOL_SCALE,
            weight = SfWeight.Semibold,
            tint = tint,
            effect = RevealBounce,
            trigger = reveals,
            contentDescription = null,
        )
    }
}

private const val REVEAL_SYMBOL_SCALE = 22f / 28f
