// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

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
