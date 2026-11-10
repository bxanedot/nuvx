package com.bxane.nuvx.ui.haptics

import androidx.compose.runtime.Composable

actual class Haptics {
    actual fun play(haptic: Haptic) = Unit
}

private val NoHaptics = Haptics()

@Composable
actual fun rememberHaptics(): Haptics = NoHaptics
