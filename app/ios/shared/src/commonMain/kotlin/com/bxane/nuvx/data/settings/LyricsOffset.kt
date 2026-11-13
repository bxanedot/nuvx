package com.bxane.nuvx.data.settings

import kotlin.math.roundToInt























object LyricsOffset {


    const val MIN_MS: Int = -5_000
    const val MAX_MS: Int = 5_000









    const val STEP_MS: Int = 100


    const val DEFAULT_MS: Int = 0






    fun minMs(): Int = MIN_MS

    fun maxMs(): Int = MAX_MS

    fun stepMs(): Int = STEP_MS

    fun defaultMs(): Int = DEFAULT_MS


    fun coerce(value: Int): Int = value.coerceIn(MIN_MS, MAX_MS)








    fun fraction(value: Int): Float =
        (coerce(value) - MIN_MS).toFloat() / (MAX_MS - MIN_MS)










    fun value(fraction: Float): Int {
        val raw = (MIN_MS + fraction.coerceIn(0f, 1f) * (MAX_MS - MIN_MS)).toInt()
        val snapped = (raw.toFloat() / STEP_MS).roundToInt() * STEP_MS
        return coerce(snapped)
    }


    fun decrease(value: Int): Int = coerce(value - STEP_MS)


    fun increase(value: Int): Int = coerce(value + STEP_MS)









    fun format(value: Int): String {
        val ms = coerce(value)
        if (ms == 0) return "0.0s"
        val sign = if (ms > 0) "+" else "−"








        val tenths = (if (ms < 0) -ms else ms) / 100
        return "$sign${tenths / 10}.${tenths % 10}s"
    }
}
