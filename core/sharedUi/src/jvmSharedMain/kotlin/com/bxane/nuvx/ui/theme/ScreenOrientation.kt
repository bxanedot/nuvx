package com.bxane.nuvx.ui.theme

import androidx.compose.runtime.Composable

interface ScreenOrientation {
    fun landscape()
    fun portrait()
}

@Composable
expect fun rememberScreenOrientation(): ScreenOrientation
