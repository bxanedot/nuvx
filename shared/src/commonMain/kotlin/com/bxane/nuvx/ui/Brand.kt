package com.bxane.nuvx.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// bxane's original 03_electric_blue.svg paths and bounding-box gradients, rendered without a 4096px bitmap allocation.
internal val NuvxMark: ImageVector by lazy {
    ImageVector.Builder("Nuvx", 1024.dp, 1024.dp, 1024f, 1024f).apply {
        addPath(
            pathData = PathParser().parsePathString("M694,101 L340,338 C301,365 283,390 283,430 L283,520 C283,571 313,608 381,609 L525,608 L470,561 C444,540 436,519 450,489 C456,476 468,460 481,440 Z").toNodes(),
            fill = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF2458FD)), Offset(283f, 101f), Offset(694f, 609f)),
        )
        addPath(
            pathData = PathParser().parsePathString("M504,454 L663,454 C717,455 757,493 757,547 L757,604 C757,656 728,701 683,729 L315,932 L557,609 C581,579 592,558 582,535 C577,519 557,503 540,486 Z").toNodes(),
            fill = Brush.linearGradient(listOf(Color(0xFF22DDFC), Color(0xFF5430EF)), Offset(315f, 454f), Offset(757f, 932f)),
        )
    }.build()
}
