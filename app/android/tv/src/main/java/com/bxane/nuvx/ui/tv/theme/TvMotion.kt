package com.bxane.nuvx.ui.tv.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

enum class AppleDuration(val millis: Int) {
    Instant(0),
    Fast(100),
    Normal(150),
    Slow(200),
    Reveal(400),
    LyricsScroll(500),
}

enum class AppleSpringPreset(val stiffness: Float, val dampingRatio: Float) {
    Snappy(400f, 0.65f),
    Smooth(200f, 0.75f),
    Gentle(120f, 0.85f),
}

fun <T> appleSpring(
    preset: AppleSpringPreset = AppleSpringPreset.Smooth,
    visibilityThreshold: T? = null,
): SpringSpec<T> = spring(
    dampingRatio = preset.dampingRatio,
    stiffness = preset.stiffness,
    visibilityThreshold = visibilityThreshold,
)
