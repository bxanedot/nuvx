// SPDX-License-Identifier: LGPL-3.0-only
package com.bxane.nuvx.playback.audio.bitperfect

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

internal object ExclusiveNative {
    init {
        System.loadLibrary("nuvx_exclusive")
    }

    external fun open(rate: Int, channels: Int, bits: Int, device: Int): Long
    external fun bits(handle: Long): Int
    external fun device(handle: Long): Int
    external fun capacity(handle: Long): Int
    external fun write(handle: Long, data: ByteBuffer, offset: Int, count: Int): Int
    external fun start(handle: Long)
    external fun pause(handle: Long)
    external fun flush(handle: Long)
    external fun frames(handle: Long): Long
    external fun close(handle: Long)
}

internal class ExclusiveTransport private constructor(
    private val manager: AudioManager,
    private var handle: Long,
    override val sampleRate: Int,
    override val channelCount: Int,
    override val outputBits: Int,
    override val deviceName: String,
    private val deviceId: Int,
) : BitPerfectTransport {

    override val driver = BitPerfectDriver.AAUDIO_EXCLUSIVE
    override val bufferSizeFrames: Long = ExclusiveNative.capacity(handle).toLong()

    @Volatile private var failure: String? = null

    private var lastFrames = 0L

    private val route = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.id == deviceId }) failure = "AAudio device disconnected"
        }
    }

    init {
        manager.registerAudioDeviceCallback(route, Handler(Looper.getMainLooper()))
    }

    override fun write(buffer: ByteBuffer): Int {
        val count = call { ExclusiveNative.write(handle, buffer, buffer.position(), minOf(buffer.remaining(), MAX_WRITE)) }
        buffer.position(buffer.position() + count)
        return count
    }

    override fun play() = call { ExclusiveNative.start(handle) }

    override fun pause() = call { ExclusiveNative.pause(handle) }

    override fun flush() {
        call { ExclusiveNative.flush(handle) }
        lastFrames = 0
    }

    override fun stop() = Unit

    override fun playedFrames(): Long {
        if (handle == 0L || failure != null) return lastFrames
        val frames = runCatching { ExclusiveNative.frames(handle) }.getOrDefault(lastFrames)
        lastFrames = maxOf(lastFrames, frames)
        return lastFrames
    }

    override fun release() {
        runCatching { manager.unregisterAudioDeviceCallback(route) }
        if (handle != 0L) ExclusiveNative.close(handle)
        handle = 0
    }

    private inline fun <T> call(block: () -> T): T {
        failure?.let { throw BitPerfectTransportException(it) }
        return try {
            block()
        } catch (error: IllegalStateException) {
            failure = error.message ?: "AAudio output failed"
            throw BitPerfectTransportException(failure!!, error)
        }
    }

    companion object {
        private const val MAX_WRITE = 262_144

        private const val MAX_RATE_ATTEMPTS = 3

        fun open(manager: AudioManager, rate: Int, channels: Int, sourceBits: Int, convert: Boolean): ExclusiveTransport {
            val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val wired = outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            val offered = wired?.sampleRates?.takeIf { it.isNotEmpty() }?.toList() ?: BitPerfectPcm.STANDARD_RATES
            val rates = if (convert) BitPerfectPcm.rankRates(rate, offered + rate).take(MAX_RATE_ATTEMPTS) else listOf(rate)
            val depths = if (convert) listOf(32, 24, 16).filter { it <= sourceBits } else listOf(sourceBits)
            var openedRate = rate
            var failure: Throwable? = null
            var handle = 0L
            search@ for (candidate in rates) {
                for (bits in depths) {
                    try {
                        handle = ExclusiveNative.open(candidate, channels, bits, wired?.id ?: 0)
                        openedRate = candidate
                        break@search
                    } catch (error: IllegalStateException) {
                        failure = error
                    } catch (error: UnsatisfiedLinkError) {
                        throw BitPerfectTransportException("exclusive_unavailable", error)
                    }
                }
            }
            if (handle == 0L) throw BitPerfectTransportException("exclusive_unavailable", failure)
            try {
                val device = ExclusiveNative.device(handle)
                val name = outputs.firstOrNull { it.id == device }?.productName?.toString()
                    ?.takeIf { it.isNotBlank() } ?: "AAudio"
                return ExclusiveTransport(
                    manager = manager,
                    handle = handle,
                    sampleRate = openedRate,
                    channelCount = channels,
                    outputBits = ExclusiveNative.bits(handle),
                    deviceName = name,
                    deviceId = device,
                )
            } catch (error: Exception) {
                ExclusiveNative.close(handle)
                throw BitPerfectTransportException("exclusive_unavailable", error)
            }
        }
    }
}
