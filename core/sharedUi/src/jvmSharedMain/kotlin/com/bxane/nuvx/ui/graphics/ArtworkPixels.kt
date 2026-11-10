package com.bxane.nuvx.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.request.ImageRequest

expect fun ImageRequest.Builder.forPixelAccess(): ImageRequest.Builder

expect fun Image.toImageBitmap(): ImageBitmap

expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

fun ImageBitmap.argbPixels(): IntArray =
    IntArray(width * height).also { readPixels(it) }

class Swatch(val rgb: Int, val population: Int)

expect fun paletteSwatches(image: ImageBitmap, maxColors: Int, clearFilters: Boolean): List<Swatch>
