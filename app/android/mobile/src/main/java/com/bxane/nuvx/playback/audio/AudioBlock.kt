package com.bxane.nuvx.playback.audio

class AudioBlock(
    val channelCount: Int = DEFAULT_CHANNELS,
    val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES,
) {
    init {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(capacityFrames > 0) { "capacityFrames must be positive: $capacityFrames" }
    }

    val samples: FloatArray = FloatArray(capacityFrames * channelCount)

    var frameCount: Int = 0
        private set

    val sampleCount: Int
        get() = frameCount * channelCount

    fun reset(frames: Int = 0) {
        require(frames in 0..capacityFrames) {
            "frames ($frames) out of range 0..$capacityFrames"
        }
        this.frameCount = frames
    }

    fun setFrameCount(frames: Int) {
        reset(frames)
    }

    fun clear() {
        if (sampleCount > 0) {
            samples.fill(0.0f, 0, sampleCount)
        }
        frameCount = 0
    }

    fun copyFrom(
        source: AudioBlock,
        sourceStartFrame: Int = 0,
        destStartFrame: Int = 0,
        frames: Int = source.frameCount - sourceStartFrame,
    ) {
        require(source.channelCount == this.channelCount) {
            "Channel count mismatch: source has ${source.channelCount}, dest has ${this.channelCount}"
        }
        require(sourceStartFrame >= 0 && sourceStartFrame + frames <= source.frameCount) {
            "Source range [$sourceStartFrame, ${sourceStartFrame + frames}) out of bounds for source frameCount ${source.frameCount}"
        }
        require(destStartFrame >= 0 && destStartFrame + frames <= capacityFrames) {
            "Dest range [$destStartFrame, ${destStartFrame + frames}) out of bounds for capacity $capacityFrames"
        }
        if (frames <= 0) return

        val srcOffset = sourceStartFrame * channelCount
        val dstOffset = destStartFrame * channelCount
        val count = frames * channelCount
        System.arraycopy(source.samples, srcOffset, this.samples, dstOffset, count)

        val newFrameCount = maxOf(this.frameCount, destStartFrame + frames)
        this.frameCount = newFrameCount
    }

    companion object {
        const val DEFAULT_CHANNELS: Int = 2
        const val DEFAULT_CAPACITY_FRAMES: Int = 4096
    }
}
