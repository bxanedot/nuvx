@file:OptIn(ExperimentalAtomicApi::class)

package com.bxane.nuvx.data.listentogether

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs


































class ServerClock {

    private data class Sample(val offsetMs: Long, val roundTripMs: Long, val takenAtMs: Long)










    private val window = AtomicReference<List<Sample>>(emptyList())

    @Volatile
    var offsetMs: Long? = null
        private set

    @Volatile
    var roundTripMs: Long = 0
        private set


    val synced: Boolean get() = offsetMs != null








    fun record(sentAtLocalMs: Long, serverMs: Long, receivedAtLocalMs: Long) {
        val roundTrip = (receivedAtLocalMs - sentAtLocalMs).coerceAtLeast(0)
        val midpoint = sentAtLocalMs + roundTrip / 2
        val fresh = Sample(serverMs - midpoint, roundTrip, receivedAtLocalMs)
        var chosen: Sample? = null
        while (true) {
            val current = window.load()
            val next = (current + fresh).takeLast(WINDOW)



            val cutoff = receivedAtLocalMs - SAMPLE_TTL
            val usable = next.filter { it.takenAtMs >= cutoff }.ifEmpty { next }
            chosen = usable.minBy { it.roundTripMs }
            if (window.compareAndSet(current, next)) break
        }
        val best = chosen ?: fresh
        offsetMs = best.offsetMs
        roundTripMs = best.roundTripMs
    }


    fun serverNowMs(localNowMs: Long): Long? = offsetMs?.let { localNowMs + it }













    fun positionFor(positionMs: Long, trueAtServerMs: Long, localNowMs: Long): Long {
        val now = serverNowMs(localNowMs) ?: return 0
        return (positionMs + (now - trueAtServerMs)).coerceAtLeast(0)
    }

    fun reset() {
        window.store(emptyList())
        offsetMs = null
        roundTripMs = 0
    }


    internal fun sampleCount(): Int = window.load().size


    internal fun sampleRoundTrips(): List<Long> = window.load().map { it.roundTripMs }

    companion object {







        const val WINDOW = 12







        const val SAMPLE_TTL = 120_000L
    }
}









const val PARTY_SYNC_TOLERANCE_MS = 750L









fun isInSync(gapMs: Long): Boolean = abs(gapMs) <= PARTY_SYNC_TOLERANCE_MS
