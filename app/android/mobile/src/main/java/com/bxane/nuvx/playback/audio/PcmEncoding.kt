package com.bxane.nuvx.playback.audio

enum class PcmEncoding(
    val bytesPerSample: Int,
    val bitDepth: Int,
) {
    PCM_16BIT(bytesPerSample = 2, bitDepth = 16),

    PCM_24BIT_PACKED(bytesPerSample = 3, bitDepth = 24),

    PCM_32BIT(bytesPerSample = 4, bitDepth = 32),

    PCM_FLOAT(bytesPerSample = 4, bitDepth = 32);

    fun bytesPerFrame(channelCount: Int): Int {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        return bytesPerSample * channelCount
    }

    fun totalBytes(frames: Int, channelCount: Int): Int {
        require(frames >= 0) { "frames must be non-negative: $frames" }
        return frames * bytesPerFrame(channelCount)
    }
}
