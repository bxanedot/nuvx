package com.bxane.nuvx.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.backdrop.Backdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.LayerBackdrop
import com.bxane.nuvx.ui.components.backdrop.drawBackdrop
import com.bxane.nuvx.ui.components.backdrop.effects.blur
import com.bxane.nuvx.ui.components.backdrop.effects.colorControls
import com.bxane.nuvx.ui.components.backdrop.effects.lens
import com.bxane.nuvx.ui.components.backdrop.highlight.Highlight
import com.bxane.nuvx.ui.components.backdrop.highlight.HighlightElement
import com.bxane.nuvx.ui.components.backdrop.internal.ShapeProvider
import com.bxane.nuvx.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp

val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
private const val SURFACE_OPACITY = 0.4f

private const val GLASS_RESOLUTION_SCALE = 0.33f

private const val FROST_BLUR_RADIUS_DP = 16f
private const val FROST_RESOLUTION_SCALE = 0.25f

internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = DARK_INDICATOR_OPACITY)
    }

private const val DARK_INDICATOR_OPACITY = 0.14f

@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassTint.copy(alpha = SURFACE_OPACITY) else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { Highlight.Default },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!LocalLiquidGlassEnabled.current || !isGlassSupported()) return this
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    return then(
        HighlightElement(
            shapeProvider = shapeProvider,
            highlight = { Highlight.Default },
        ),
    )
}

@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    surfaceTint: Color? = null,
): Modifier {
    if (!isGlassSupported()) return this
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val backdrop = LocalAppBackdrop.current
    val exportedBackdrop = LocalGlassExport.current
    val density = LocalDensity.current
    val resolutionScale = if (glassPerformance) FROST_RESOLUTION_SCALE else GLASS_RESOLUTION_SCALE
    val blurRadiusDp = if (glassPerformance) FROST_BLUR_RADIUS_DP else BLUR_RADIUS_DP
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * resolutionScale
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val themeTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    val surfaceTintColor = surfaceTint ?: themeTint.copy(alpha = SURFACE_OPACITY)

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            if (!glassPerformance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = {
            drawRect(color = surfaceTintColor, size = size)
        },
        exportedBackdrop = exportedBackdrop,
        backdropScale = resolutionScale,
    )
}
