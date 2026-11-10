package com.bxane.nuvx.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

object SleepTimer {

    val deadline = MutableStateFlow<Long?>(null)

    val minutes = MutableStateFlow<Int?>(null)

    val afterTrack = MutableStateFlow(false)

    val PRESETS = listOf(15, 30, 45, 60)

    const val STEP_MINUTES = 5

    const val MAX_MINUTES = 720

    fun minutesAt(fraction: Float): Int {
        val raw = STEP_MINUTES + fraction.coerceIn(0f, 1f) * (MAX_MINUTES - STEP_MINUTES)
        return (raw / STEP_MINUTES).roundToInt() * STEP_MINUTES
    }

    fun fractionOf(minutes: Int): Float =
        (minutes - STEP_MINUTES).toFloat() / (MAX_MINUTES - STEP_MINUTES)

    val isRunning: Boolean get() = deadline.value != null || afterTrack.value

    fun start(minutes: Int) {
        afterTrack.value = false
        this.minutes.value = minutes
        deadline.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun startAfterTrack() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = true
    }

    fun cancel() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
    }

    fun remainingMs(): Long? =
        deadline.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
}
