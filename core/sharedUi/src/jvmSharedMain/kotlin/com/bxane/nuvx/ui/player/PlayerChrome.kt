package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSupported
import androidx.compose.ui.graphics.layer.drawLayer

internal val LocalPlayerChrome = compositionLocalOf { false }

internal const val CHROME_FULL_ALPHA = 0.73f

private const val CHROME_ALPHA_DROP = 0.15f

@Composable
@ReadOnlyComposable
internal fun chromeWhite(alpha: Float): Color =
    Color.White.copy(alpha = if (LocalPlayerChrome.current) alpha - CHROME_ALPHA_DROP else alpha)

internal val VibrantChromeInk = Color(0xFF999999).copy(alpha = 1f - CHROME_ALPHA_DROP)

private const val VIBRANT_CHROME_FLOOR = 0.12f

fun Modifier.additiveChrome(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            val layer = obtainGraphicsLayer().apply { blendMode = BlendMode.Plus }
            onDrawWithContent {
                layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(layer)
            }
        }
    }

internal fun Modifier.vibrantChrome(enabled: Boolean): Modifier =
    if (!enabled || !BlendMode.ColorDodge.isSupported()) {
        additiveChrome(enabled)
    } else {
        drawWithCache {
            val floor = obtainGraphicsLayer().apply {
                blendMode = BlendMode.Plus
                alpha = VIBRANT_CHROME_FLOOR
            }
            val dodge = obtainGraphicsLayer().apply { blendMode = BlendMode.ColorDodge }
            onDrawWithContent {
                floor.record { this@onDrawWithContent.drawContent() }
                dodge.record { this@onDrawWithContent.drawContent() }
                drawLayer(floor)
                drawLayer(dodge)
            }
        }
    }
