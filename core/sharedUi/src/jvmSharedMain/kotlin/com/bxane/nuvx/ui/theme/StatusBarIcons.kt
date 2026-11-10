package com.bxane.nuvx.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

@Composable
expect fun StatusBarIcons(dark: Boolean)

@Composable
expect fun HideStatusBar(hidden: Boolean)

@Composable
expect fun HideSystemBars(hidden: Boolean)

@Composable
expect fun stableStatusBarInsets(): WindowInsets
