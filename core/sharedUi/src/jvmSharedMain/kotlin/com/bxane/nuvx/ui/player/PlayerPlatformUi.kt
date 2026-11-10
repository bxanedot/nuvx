package com.bxane.nuvx.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope

internal expect fun uptimeMillis(): Long

internal expect val renderEffectBlurSupported: Boolean

@Composable
internal expect fun KeepScreenOn(enabled: Boolean)

@Composable
internal expect fun appLanguageTag(): String

@Composable
expect fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit)

internal expect val drawerFollowsListScroll: Boolean

internal expect fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
)
