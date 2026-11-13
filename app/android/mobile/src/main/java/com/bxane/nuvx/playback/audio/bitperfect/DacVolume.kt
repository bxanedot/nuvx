
package com.bxane.nuvx.playback.audio.bitperfect

import com.bxane.nuvx.playback.audio.bitperfect.usb.UsbVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DacVolume {

    const val STEPS = 16

    private const val FLOOR_DB = -80.0

    private val _level = MutableStateFlow<Float?>(null)
    val level: StateFlow<Float?> = _level.asStateFlow()

    private var snapshot: UsbVolume.Snapshot? = null

    @Synchronized
    internal fun onSnapshot(value: UsbVolume.Snapshot?) {
        snapshot = value
        _level.value = value?.takeIf { it.available }?.let(::fractionOf)
    }

    @Synchronized
    fun set(fraction: Float) {
        val volume = snapshot?.takeIf { it.available } ?: return
        val target = fraction.coerceIn(0f, 1f)
        _level.value = target
        BitPerfectOutput.setHardwareVolume(dbOf(volume, target))
    }

    @Synchronized
    fun step(direction: Int): Boolean {
        val current = _level.value ?: return false
        val next = ((Math.round(current * STEPS) + direction).coerceIn(0, STEPS)).toFloat() / STEPS
        if (next == current) return false
        set(next)
        return true
    }

    internal fun fractionOf(volume: UsbVolume.Snapshot): Float {
        val floor = maxOf(volume.minDb, FLOOR_DB)
        if (volume.maxDb <= floor || volume.currentDb <= floor) return 0f
        return ((volume.currentDb - floor) / (volume.maxDb - floor)).toFloat().coerceIn(0f, 1f)
    }

    internal fun dbOf(volume: UsbVolume.Snapshot, fraction: Float): Double {
        if (fraction <= 0f) return volume.minDb
        val floor = maxOf(volume.minDb, FLOOR_DB)
        return floor + fraction * (volume.maxDb - floor)
    }
}
