package com.bxane.nuvx.data

object MonotonicClock {
    private val origin = System.nanoTime()

    @Volatile
    var nowMs: () -> Long = { (System.nanoTime() - origin) / 1_000_000L }
}
