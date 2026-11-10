package com.bxane.nuvx.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable

@Composable
actual fun StatusBarIcons(dark: Boolean) = Unit

@Composable
actual fun HideStatusBar(hidden: Boolean) = Unit

@Composable
actual fun HideSystemBars(hidden: Boolean) = Unit

@Composable
actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBars
