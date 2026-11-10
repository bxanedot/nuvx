package com.bxane.nuvx.ui.components

import androidx.compose.ui.Modifier

expect fun Modifier.contextClick(onClick: (() -> Unit)?): Modifier
