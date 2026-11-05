/*
 * Adapted from bxane's supplied Nuvx_GlassEffect_bxane.kt (GPL-3.0); backdrop
 * primitives are vendored from Kyant0/backdrop v2.0.0 (Apache-2.0).
 * See THIRD_PARTY_NOTICES.md for attribution and licences.
 */
package com.bxane.nuvx

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CornerBasedShape
import com.bxane.nuvx.backdrop.backdrops.LayerBackdrop
import com.bxane.nuvx.backdrop.backdrops.layerBackdrop
import com.bxane.nuvx.backdrop.backdrops.rememberLayerBackdrop
import com.bxane.nuvx.backdrop.drawBackdrop
import com.bxane.nuvx.backdrop.effects.blur
import com.bxane.nuvx.backdrop.effects.colorControls
import com.bxane.nuvx.backdrop.effects.lens
import com.bxane.nuvx.backdrop.highlight.Highlight
import com.bxane.nuvx.backdrop.shadow.Shadow
import com.bxane.nuvx.ui.LocalNuvxGlassContentCapture
import com.bxane.nuvx.ui.LocalNuvxGlassRenderer
import com.bxane.nuvx.ui.NuvxGlassContentCapture
import com.bxane.nuvx.ui.NuvxGlassRenderer
import com.bxane.nuvx.ui.NuvxGlassSpec

private const val MIN_CAPTURE_SCALE = 0.30f
private const val FULL_QUALITY_BLUR_DP = 2.5f

private fun glassResolutionScale(blurRadiusDp: Float): Float {
    val fraction = (blurRadiusDp / FULL_QUALITY_BLUR_DP).coerceIn(0f, 1f)
    return 1f - fraction * (1f - MIN_CAPTURE_SCALE)
}

@Composable
internal fun AndroidGlassHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lowRam = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || lowRam) {
        content()
        return
    }

    val backdrop = rememberLayerBackdrop()
    val renderer: NuvxGlassRenderer = { modifier, shape, liquid, dark, spec ->
        modifier.androidGlass(backdrop, shape, liquid, dark, spec)
    }
    val captureContent: NuvxGlassContentCapture = { captured ->
        CompositionLocalProvider(LocalNuvxGlassRenderer provides null) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { captured() }
        }
    }
    CompositionLocalProvider(
        LocalNuvxGlassRenderer provides renderer,
        LocalNuvxGlassContentCapture provides captureContent,
    ) { content() }
}

@Composable
private fun Modifier.androidGlass(
    backdrop: LayerBackdrop,
    shape: Shape,
    liquid: Boolean,
    dark: Boolean,
    spec: NuvxGlassSpec,
): Modifier {
    val density = LocalDensity.current
    val captureScale = glassResolutionScale(spec.blurRadius.value)
    val blurPx = with(density) { spec.blurRadius.toPx() * captureScale }
    val refractionHeightPx = with(density) { spec.refractionHeight.toPx() * captureScale }
    val refractionAmountPx = with(density) { spec.refractionAmount.toPx() * captureScale }
    val tint = (if (dark) Color(0xFF17171B) else Color(0xFFF8F8FA)).copy(alpha = if (dark) 0.24f else 0.20f)

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = spec.saturation.coerceIn(1f, 2f))
            blur(blurPx)
            if (liquid && shape is CornerBasedShape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeightPx,
                    refractionAmountPx,
                    depthEffect = spec.depthEffect,
                    chromaticAberration = spec.chromaticAberration,
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(color = tint, size = size) },
        backdropScale = captureScale,
    )
}
