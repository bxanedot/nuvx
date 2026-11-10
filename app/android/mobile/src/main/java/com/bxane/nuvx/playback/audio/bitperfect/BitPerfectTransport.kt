// SPDX-License-Identifier: Apache-2.0
package com.bxane.nuvx.playback.audio.bitperfect

import java.nio.ByteBuffer

internal interface BitPerfectTransport {
    val driver: BitPerfectDriver
    val deviceName: String
    val sampleRate: Int
    val channelCount: Int

    val outputBits: Int

    val validBits: Int get() = outputBits

    val bufferSizeFrames: Long

    fun write(buffer: ByteBuffer): Int

    fun play()

    fun pause()

    fun flush()

    fun stop()

    fun playedFrames(): Long

    fun release()
}

internal enum class BitPerfectDriver(val label: String, val releasesWhenIdle: Boolean) {
    ANDROID_MIXER("Android bit-perfect mixer", releasesWhenIdle = false),
    USB_DIRECT("Direct USB", releasesWhenIdle = true),
    AAUDIO_EXCLUSIVE("AAudio exclusive", releasesWhenIdle = true),
}

internal class BitPerfectTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
