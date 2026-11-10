package com.bxane.nuvx.playback.audio

interface FloatAudioProcessor {
    fun configure(sampleRate: Int, channelCount: Int = AudioBlock.DEFAULT_CHANNELS)

    fun process(block: AudioBlock)

    fun flush()

    fun reset()
}

fun com.bxane.nuvx.playback.SpatialAudioProcessor.asFloatAudioProcessor(): FloatAudioProcessor =
    object : FloatAudioProcessor {
        override fun configure(sampleRate: Int, channelCount: Int) =
            this@asFloatAudioProcessor.configure(sampleRate, channelCount)
        override fun process(block: AudioBlock) =
            this@asFloatAudioProcessor.process(block)
        @Suppress("DEPRECATION")
        override fun flush() = this@asFloatAudioProcessor.flush()
        override fun reset() = this@asFloatAudioProcessor.reset()
    }

fun com.bxane.nuvx.playback.EqualizerProcessor.asFloatAudioProcessor(): FloatAudioProcessor =
    object : FloatAudioProcessor {
        override fun configure(sampleRate: Int, channelCount: Int) =
            this@asFloatAudioProcessor.configure(sampleRate, channelCount)
        override fun process(block: AudioBlock) =
            this@asFloatAudioProcessor.process(block)
        @Suppress("DEPRECATION")
        override fun flush() = this@asFloatAudioProcessor.flush()
        override fun reset() = this@asFloatAudioProcessor.reset()
    }

fun com.bxane.nuvx.playback.TransitionFilterProcessor.asFloatAudioProcessor(): FloatAudioProcessor =
    object : FloatAudioProcessor {
        override fun configure(sampleRate: Int, channelCount: Int) =
            this@asFloatAudioProcessor.configure(sampleRate, channelCount)
        override fun process(block: AudioBlock) =
            this@asFloatAudioProcessor.process(block)
        @Suppress("DEPRECATION")
        override fun flush() = this@asFloatAudioProcessor.flush()
        override fun reset() = this@asFloatAudioProcessor.reset()
    }

