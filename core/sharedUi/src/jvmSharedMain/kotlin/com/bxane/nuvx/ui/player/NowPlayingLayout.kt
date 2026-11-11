package com.bxane.nuvx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

internal class DockFrame {
    var anchor: Offset? by mutableStateOf(null)
    var anchorFor = IntSize.Zero
    var miniInBox: Rect? = null
    var offset = 0f
    var ride = 0f
    var sleeve: Rect = Rect.Zero
    private var cornerRadius = Dp.Unspecified
    private var corner: Shape = RectangleShape
    fun cornerShape(radius: Dp): Shape {
        if (radius != cornerRadius) {
            cornerRadius = radius
            corner = RoundedCornerShape(radius)
        }
        return corner
    }
    var portalLeft = 0f
    var portalTop = 0f
    var portalScale = 1f
    var host: LayoutCoordinates? = null
    var landscapeArt: LayoutCoordinates? = null
}

internal class LandscapeDockPose(val left: Float, val top: Float, val scale: Float)

@Composable
internal fun WhileShown(shown: () -> Boolean, content: @Composable () -> Unit) {
    if (shown()) content()
}

fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

internal fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

internal fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

internal fun playerDeckSlideIn() = slideInVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    initialOffsetY = { it },
)

internal fun playerDeckSlideOut() = slideOutVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    targetOffsetY = { it },
)

@Composable
internal fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    fadeIn: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (fadeIn() && reveal.value < 1f) {
                alpha.snapTo(0f)
                reveal.snapTo(1f)
                mounted = true
                alpha.animateTo(
                    1f,
                    tween(PLAYER_DECK_FADE_IN_MS, easing = LinearEasing),
                )
            } else {
                alpha.snapTo(1f)
                mounted = true
                reveal.animateTo(
                    1f,
                    tween(
                        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.animateTo(
                0f,
                tween(
                    SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        Box(
            modifier = modifier
                .graphicsLayer {
                    this.alpha = alpha.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val animatedHeight = (placeable.height * reveal.value)
                        .roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(placeable.width, animatedHeight) {
                        placeable.placeRelative(0, 0)
                    }
                },
        ) {
            content()
        }
    }
}
