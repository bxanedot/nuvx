package com.bxane.nuvx.ui.graphics

fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

const val FADE_CURVE_STOPS = 16
