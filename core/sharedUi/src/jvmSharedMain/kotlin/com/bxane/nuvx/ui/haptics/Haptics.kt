package com.bxane.nuvx.ui.haptics

import androidx.compose.runtime.Composable

enum class Haptic {
    Tick,

    Tap,

    Select,

    ToggleOn,

    ToggleOff,

    SkipNext,

    SkipPrevious,

    Resume,

    Pause,

    Expand,

    Pulse,
}

expect class Haptics {
    fun play(haptic: Haptic)
}

@Composable
expect fun rememberHaptics(): Haptics
