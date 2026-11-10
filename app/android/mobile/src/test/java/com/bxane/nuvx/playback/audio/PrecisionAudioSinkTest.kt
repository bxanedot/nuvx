package com.bxane.nuvx.playback.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.EqualizerProcessor
import com.bxane.nuvx.playback.SpatialAudioProcessor
import com.bxane.nuvx.playback.TransitionFilterProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(UnstableApi::class)
class PrecisionAudioSinkTest {

    private class FakeAudioSink : AudioSink {
        var configuredConfig: AudioSink.AudioSinkConfig? = null
        private var sinkListener: AudioSink.Listener? = null
        var flushed: Boolean = false
        var resetCount: Int = 0
        var discontinuityHandled: Boolean = false
        var playedToEndOfStream: Boolean = false
        var isEndedReturn: Boolean = false
        var hasPendingDataReturn: Boolean = false

        var bytesToConsumePerCall: Int = Int.MAX_VALUE
        var lastHandledBuffer: ByteBuffer? = null
        var handleBufferCallCount: Int = 0
        var lastPresentationTimeUs: Long = C.TIME_UNSET
        var lastEncodedAccessUnitCount: Int = 0

        var formatSupportReturn: Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        var supportsFormatReturn: Boolean = true
        var supportsFormatPredicate: ((Format) -> Boolean)? = null

        var formatSupportPredicate: ((Format) -> Int)? = null

        override fun setListener(listener: AudioSink.Listener) {
            this.sinkListener = listener
        }

        override fun supportsFormat(format: Format): Boolean {
            return supportsFormatPredicate?.invoke(format) ?: supportsFormatReturn
        }

        override fun getFormatSupport(format: Format): Int =
            formatSupportPredicate?.invoke(format) ?: formatSupportReturn

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            this.configuredConfig = audioSinkConfig
        }

        override fun play() {}

        override fun handleDiscontinuity() {
            discontinuityHandled = true
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int,
        ): Boolean {
            handleBufferCallCount++
            lastHandledBuffer = buffer
            lastPresentationTimeUs = presentationTimeUs
            lastEncodedAccessUnitCount = encodedAccessUnitCount

            val remaining = buffer.remaining()
            val consume = minOf(remaining, bytesToConsumePerCall)
            buffer.position(buffer.position() + consume)

            return !buffer.hasRemaining()
        }

        override fun playToEndOfStream() {
            playedToEndOfStream = true
        }

        override fun isEnded(): Boolean = isEndedReturn

        override fun hasPendingData(): Boolean = hasPendingDataReturn

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

        override fun getSkipSilenceEnabled(): Boolean = false

        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}

        override fun getAudioAttributes(): AudioAttributes? = null

        override fun setAudioSessionId(audioSessionId: Int) {}

        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

        override fun getAudioTrackBufferSizeUs(): Long = 0L

        override fun enableTunnelingV21() {}

        override fun disableTunneling() {}

        override fun setVolume(volume: Float) {}

        override fun pause() {}

        override fun flush() {
            flushed = true
        }

        override fun reset() {
            resetCount++
            configuredConfig = null
        }

        override fun release() {}
    }

    private fun createSink(
        delegate: AudioSink,
        enableFloatOutput: Boolean = true,
        dspChain: DspChain = idleDspChain(),
    ): PrecisionAudioSink {
        return PrecisionAudioSink(
            delegate = delegate,
            dspChain = dspChain,
            enableFloatOutput = enableFloatOutput,
        )
    }

    private fun rawFormat(pcmEncoding: Int, channelCount: Int = 2): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(pcmEncoding)
        .setChannelCount(channelCount)
        .setSampleRate(48000)
        .build()

    private fun idleDspChain(): DspChain = DspChain(
        spatial = SpatialAudioProcessor(),
        equalizer = EqualizerProcessor(),
        transition = TransitionFilterProcessor(),
    )

    @Test
    fun `16-bit round trip is exact with an idle chain`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertTrue(AudioOutputStatus.current.value.outputExact)

        val samples = shortArrayOf(
            0, 1, -1, 1000, -1000, 32767, -32768, 12345, -12345, 255, -256, 4096,
        )
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        samples.forEachIndexed { i, expected ->
            assertEquals("sample $i altered", expected, handed.getShort())
        }
    }

    @Test
    fun `24-bit goes to float and survives exactly`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        assertEquals(PcmEncoding.PCM_24BIT_PACKED, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
        assertTrue(AudioOutputStatus.current.value.outputExact)

        val samples = intArrayOf(0, 1, -1, 8388607, -8388608, 0x0000FF, 0x7FFFFF, -0x7FFFFF)
        val input = ByteBuffer.allocate(samples.size * 3).order(ByteOrder.nativeOrder())
        samples.forEach {
            input.put((it and 0xFF).toByte())
            input.put(((it shr 8) and 0xFF).toByte())
            input.put(((it shr 16) and 0xFF).toByte())
        }
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        samples.forEachIndexed { i, expected ->
            val recovered = Math.round(handed.getFloat() * 8388608.0f)
            assertEquals("sample $i altered", expected, recovered)
        }
    }

    @Test
    fun `reports inexact when the route refuses float`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertFalse(AudioOutputStatus.current.value.outputExact)
    }

    @Test
    fun `float is taken only when the delegate supports it directly`() {
        val transcoding = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            supportsFormatReturn = true
        }
        val direct = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }

        createSink(transcoding).also {
            it.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
            assertEquals(PcmEncoding.PCM_16BIT, it.targetOutputEncoding)
        }
        createSink(direct).also {
            it.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
            assertEquals(PcmEncoding.PCM_FLOAT, it.targetOutputEncoding)
        }
    }

    @Test
    fun `float input is not claimed as direct when float output is off`() {
        val floatOff = createSink(FakeAudioSink(), enableFloatOutput = false)
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            floatOff.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)),
        )
        assertTrue(floatOff.supportsFormat(rawFormat(C.ENCODING_PCM_FLOAT)))

        val floatOn = createSink(FakeAudioSink(), enableFloatOutput = true)
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            floatOn.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)),
        )
    }

    @Test
    fun `32-bit PCM is admitted to be inexact`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_32BIT)).build())

        assertFalse(AudioOutputStatus.current.value.outputExact)
        assertTrue(
            AudioOutputStatus.current.value.outputExactDetail.orEmpty().contains("8 bits lost"),
        )
    }

    @Test
    fun `precision stays active for a 5 point 1 stream`() {
        val fakeDelegate = FakeAudioSink()
        val equalizer = EqualizerProcessor().apply {
            setTuning(enabled = true, curve = com.bxane.nuvx.playback.EqCurve.FLAT, balance = 0f)
        }
        val sink = createSink(
            fakeDelegate,
            enableFloatOutput = false,
            dspChain = DspChain(equalizer = equalizer),
        )
        sink.configure(
            AudioSink.AudioSinkConfig.Builder(
                rawFormat(C.ENCODING_PCM_16BIT, channelCount = 6),
            ).build(),
        )

        assertTrue(sink.isPrecisionActive)
        assertTrue(AudioOutputStatus.current.value.dspAvailable)
        assertEquals(6, fakeDelegate.configuredConfig!!.format.channelCount)
    }

    @Test
    fun `a passthrough bitstream reports the DSP chain as unavailable`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        val eac3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        sink.configure(AudioSink.AudioSinkConfig.Builder(eac3).build())

        assertFalse(sink.isPrecisionActive)
        assertFalse(AudioOutputStatus.current.value.dspAvailable)
    }

    @Test
    fun `split sub-blocks advance the presentation timestamp`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val frames = PrecisionAudioSink.DEFAULT_CAPACITY_FRAMES * 2
        val input = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { input.putShort(0) }
        input.flip()

        sink.handleBuffer(input, 1_000_000L, 1)

        val expected = 1_000_000L + Util.sampleCountToDurationUs(
            PrecisionAudioSink.DEFAULT_CAPACITY_FRAMES.toLong(),
            48000,
        )
        assertEquals(2, fakeDelegate.handleBufferCallCount)
        assertEquals(expected, fakeDelegate.lastPresentationTimeUs)
    }

    @Test
    fun `the DSP chain is reached when it is switched on`() {
        val equalizer = EqualizerProcessor().apply {
            setTuning(enabled = true, curve = com.bxane.nuvx.playback.EqCurve.FLAT, balance = 1f)
        }
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(
            fakeDelegate,
            enableFloatOutput = false,
            dspChain = DspChain(equalizer = equalizer),
        )
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        shortArrayOf(8000, 8000, 8000, 8000).forEach(input::putShort)
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        assertEquals(0.toShort(), handed.getShort())
    }

    @Test
    fun `configure activates precision mode with Float32 output when float output enabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_16BIT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(inputFormat, sink.activeFormat)

        assertNotNull(fakeDelegate.configuredConfig)
        val delegateFormat = fakeDelegate.configuredConfig!!.format
        assertEquals(C.ENCODING_PCM_FLOAT, delegateFormat.pcmEncoding)
        assertEquals(2, delegateFormat.channelCount)
        assertEquals(48000, delegateFormat.sampleRate)
    }

    @Test
    fun `configure activates precision mode with PCM16 output when float output disabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_16BIT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
    }

    @Test
    fun `configure activates precision mode with PCM16 output when delegate rejects float`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportPredicate = { format ->
            if (format.pcmEncoding == C.ENCODING_PCM_16BIT) {
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            } else {
                AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            }
        }

        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_24BIT_PACKED, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
    }

    @Test
    fun `configure falls back for non-raw audio mime types`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertEquals(MimeTypes.AUDIO_AAC, fakeDelegate.configuredConfig!!.format.sampleMimeType)
    }

    @Test
    fun `configure falls back beyond the channel ceiling`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(PrecisionAudioSink.MAX_CHANNELS + 1)
            .setSampleRate(48000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertEquals(
            PrecisionAudioSink.MAX_CHANNELS + 1,
            fakeDelegate.configuredConfig!!.format.channelCount,
        )
    }

    @Test
    fun `configure activates for PCM 16bit, 24bit, 32bit, and Float32`() {
        val encodings = listOf(
            C.ENCODING_PCM_16BIT to PcmEncoding.PCM_16BIT,
            C.ENCODING_PCM_24BIT to PcmEncoding.PCM_24BIT_PACKED,
            C.ENCODING_PCM_32BIT to PcmEncoding.PCM_32BIT,
            C.ENCODING_PCM_FLOAT to PcmEncoding.PCM_FLOAT,
        )

        for ((pcmEncoding, expectedEncoding) in encodings) {
            val fakeDelegate = FakeAudioSink()
            val sink = createSink(fakeDelegate, enableFloatOutput = true)
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(pcmEncoding)
                .setChannelCount(2)
                .setSampleRate(96000)
                .build()

            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            assertTrue("Expected precision active for encoding $pcmEncoding", sink.isPrecisionActive)
            assertEquals(expectedEncoding, sink.inputPcmEncoding)
            assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
            assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
        }
    }

    @Test
    fun `handleBuffer converts PCM16 to Float32 and passes to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putShort(16384.toShort())
            inputBuffer.putShort((-16384).toShort())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 123456L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(123456L, fakeDelegate.lastPresentationTimeUs)
        assertEquals(1, fakeDelegate.lastEncodedAccessUnitCount)

        assertEquals(frameCount * 2 * 4, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getFloat()
        val rightSample = delegateBuffer.getFloat()
        assertEquals(0.5f, leftSample, 0.01f)
        assertEquals(-0.5f, rightSample, 0.01f)
    }

    @Test
    fun `handleBuffer converts PCM16 in to Float32 DSP to PCM16 out when float disabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putShort(16384.toShort())
            inputBuffer.putShort((-16384).toShort())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 555L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(frameCount * 2 * 2, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getShort()
        val rightSample = delegateBuffer.getShort()
        assertEquals(16384, leftSample.toInt())
        assertEquals(-16384, rightSample.toInt())
    }

    @Test
    fun `handleBuffer converts PCM24 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x40.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0xC0.toByte())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 100L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(frameCount * 2 * 2, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getShort()
        val rightSample = delegateBuffer.getShort()
        assertEquals(16384, leftSample.toInt())
        assertEquals(-16384, rightSample.toInt())
    }

    @Test
    fun `handleBuffer converts PCM32 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putInt(1073741824)
            inputBuffer.putInt(-1073741824)
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 200L, 1)
        assertTrue(handled)

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(frameCount * 2 * 2, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getShort()
        val rightSample = delegateBuffer.getShort()
        assertEquals(16384, leftSample.toInt())
        assertEquals(-16384, rightSample.toInt())
    }

    @Test
    fun `handleBuffer converts Float32 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putFloat(0.5f)
            inputBuffer.putFloat(-0.5f)
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 300L, 1)
        assertTrue(handled)

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(frameCount * 2 * 2, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getShort()
        val rightSample = delegateBuffer.getShort()
        assertEquals(16384, leftSample.toInt())
        assertEquals(-16384, rightSample.toInt())
    }

    @Test
    fun `handleBuffer respects backpressure and reuses identical buffer instance with Float32 output`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.bytesToConsumePerCall = 256

        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putShort(8000.toShort())
        }
        inputBuffer.flip()

        val result1 = sink.handleBuffer(inputBuffer, 1000L, 1)
        assertFalse("Must return false when delegate has backpressure", result1)
        val firstBufferRef = fakeDelegate.lastHandledBuffer
        assertNotNull(firstBufferRef)
        assertEquals(256, firstBufferRef!!.position())
        assertEquals(1024, firstBufferRef.limit())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        val result2 = sink.handleBuffer(inputBuffer, 1000L, 1)
        assertTrue("Must return true once delegate fully consumes", result2)
        val secondBufferRef = fakeDelegate.lastHandledBuffer

        assertSame("Must reuse exact same ByteBuffer instance across backpressure ticks", firstBufferRef, secondBufferRef)
        assertEquals(1024, secondBufferRef!!.position())
    }

    @Test
    fun `handleBuffer respects backpressure and reuses identical buffer instance with PCM16 output`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.bytesToConsumePerCall = 100

        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putShort(4000.toShort())
        }
        inputBuffer.flip()

        val result1 = sink.handleBuffer(inputBuffer, 2000L, 1)
        assertFalse("Must return false when delegate has backpressure", result1)
        val firstBufferRef = fakeDelegate.lastHandledBuffer
        assertNotNull(firstBufferRef)
        assertEquals(100, firstBufferRef!!.position())
        assertEquals(512, firstBufferRef.limit())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        val result2 = sink.handleBuffer(inputBuffer, 2000L, 1)
        assertTrue("Must return true once delegate fully consumes", result2)
        val secondBufferRef = fakeDelegate.lastHandledBuffer

        assertSame("Must reuse exact same ByteBuffer instance across backpressure ticks", firstBufferRef, secondBufferRef)
        assertEquals(512, secondBufferRef!!.position())
    }

    @Test
    fun `handleBuffer in fallback mode forwards untouched to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val inputBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.putInt(0x12345678)
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 5000L, 0)
        assertTrue(handled)
        assertSame("Fallback mode forwards inputBuffer directly without copy", inputBuffer, fakeDelegate.lastHandledBuffer)
    }

    @Test
    fun `flush and reset clear state and propagate to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        sink.flush()
        assertTrue(fakeDelegate.flushed)
        assertTrue(sink.isPrecisionActive)

        sink.reset()
        assertEquals(1, fakeDelegate.resetCount)
        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertNull(sink.activeFormat)
    }

    @Test
    fun `handleDiscontinuity forwards to delegate without resetting precision state`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        sink.handleDiscontinuity()
        assertTrue(fakeDelegate.discontinuityHandled)
        assertTrue(sink.isPrecisionActive)
    }

    @Test
    fun `getFormatSupport reports SINK_FORMAT_SUPPORTED_DIRECTLY for all linear PCM encodings with float output enabled`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val floatFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(floatFormat))

        val pcm16Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm16Format))

        val pcm24Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm24Format))

        val pcm32Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .setChannelCount(2)
            .setSampleRate(192000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm32Format))
    }

    @Test
    fun `getFormatSupport with float output disabled reports integer PCM directly and float as transcoding`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val floatFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            sink.getFormatSupport(floatFormat),
        )

        val pcm16Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm16Format))

        val pcm24Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm24Format))

        val pcm32Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .setChannelCount(2)
            .setSampleRate(192000)
            .build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm32Format))
    }

    @Test
    fun `supportsFormat returns true when precision path can transcode to supported output`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.supportsFormatPredicate = { format ->
            format.pcmEncoding == C.ENCODING_PCM_16BIT
        }
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val pcm24Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()

        assertTrue("Precision path must report format supported when delegate accepts PCM16 output", sink.supportsFormat(pcm24Format))

        val floatFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()

        assertTrue("Precision path must report float supported when delegate accepts PCM16 output", sink.supportsFormat(floatFormat))
    }

    @Test
    fun `large input buffer processed in chunks`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val totalFrames = 8192
        val inputBuffer = ByteBuffer.allocateDirect(totalFrames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames * 2) {
            inputBuffer.putShort(1000.toShort())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 0L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())
        assertEquals(2, fakeDelegate.handleBufferCallCount)
    }

    @Test
    fun `handleBuffer executes DspChain modifying samples before passing to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val eq = EqualizerProcessor()
        val dspChain = DspChain(equalizer = eq)
        val sink = createSink(fakeDelegate, enableFloatOutput = true, dspChain = dspChain)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        eq.setTuning(enabled = true, com.bxane.nuvx.playback.EqCurve(FloatArray(10), FloatArray(10), 6.0f), balance = 0f)

        val frameCount = 2048
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putFloat(0.25f)
        }
        inputBuffer.flip()

        sink.handleBuffer(inputBuffer, 0L, 1)

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        delegateBuffer!!.position((frameCount - 1) * 2 * 4)
        val leftSample = delegateBuffer.getFloat()
        val rightSample = delegateBuffer.getFloat()
        assertEquals(0.5f, leftSample, 0.05f)
        assertEquals(0.5f, rightSample, 0.05f)
    }

    @Test
    fun `handleBuffer executes DspChain modifying samples with PCM16 output`() {
        val fakeDelegate = FakeAudioSink()
        val eq = EqualizerProcessor()
        val dspChain = DspChain(equalizer = eq)
        val sink = createSink(fakeDelegate, enableFloatOutput = false, dspChain = dspChain)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        eq.setTuning(enabled = true, com.bxane.nuvx.playback.EqCurve(FloatArray(10), FloatArray(10), 6.0f), balance = 0f)

        val frameCount = 2048
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putShort(8192.toShort())
        }
        inputBuffer.flip()

        sink.handleBuffer(inputBuffer, 0L, 1)

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        delegateBuffer!!.position((frameCount - 1) * 2 * 2)
        val leftSample = delegateBuffer.getShort()
        val rightSample = delegateBuffer.getShort()
        assertEquals(16384f, leftSample.toFloat(), 1500f)
        assertEquals(16384f, rightSample.toFloat(), 1500f)
    }

    @Test
    fun `playToEndOfStream, isEnded, and hasPendingData reflect pending buffer state`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.bytesToConsumePerCall = 100

        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val inputBuffer = ByteBuffer.allocateDirect(1000).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 250) inputBuffer.putFloat(0.1f)
        inputBuffer.flip()

        sink.handleBuffer(inputBuffer, 0L, 1)

        assertTrue("Sink must report pending data when output buffer has remaining bytes", sink.hasPendingData())
        assertFalse("Sink cannot report ended when output buffer has remaining bytes", sink.isEnded())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        sink.playToEndOfStream()
        assertTrue(fakeDelegate.playedToEndOfStream)
    }

    @Test
    fun `trailing incomplete frame bytes are dropped without hanging`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val inputBuffer = ByteBuffer.allocateDirect(6).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.putShort(100)
        inputBuffer.putShort(200)
        inputBuffer.put(0x12)
        inputBuffer.put(0x34)
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 0L, 1)
        assertTrue(handled)
        assertFalse("Trailing incomplete frame bytes must be consumed so buffer finishes", inputBuffer.hasRemaining())
    }

    @Test
    fun `a 24-bit preference is honoured as float, since Media3 has no 24-bit track`() {
        val fakeDelegate = FakeAudioSink()
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = DspChain(spatial = SpatialAudioProcessor(), equalizer = EqualizerProcessor(), transition = TransitionFilterProcessor()),
            enableFloatOutput = true,
            preferredOutputEncodingProvider = { PcmEncoding.PCM_24BIT_PACKED },
        )

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(inputFormat).build())

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }

    @Test
    fun `a 24-bit preference falls back to 16-bit without a float track`() {
        val fakeDelegate = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = DspChain(spatial = SpatialAudioProcessor(), equalizer = EqualizerProcessor(), transition = TransitionFilterProcessor()),
            enableFloatOutput = true,
            preferredOutputEncodingProvider = { PcmEncoding.PCM_24BIT_PACKED },
        )

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(inputFormat).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }

    @Test
    fun `preferredOutputEncodingProvider directs target encoding to 16-bit PCM on phone speaker`() {
        val fakeDelegate = FakeAudioSink()
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = DspChain(spatial = SpatialAudioProcessor(), equalizer = EqualizerProcessor(), transition = TransitionFilterProcessor()),
            enableFloatOutput = true,
            preferredOutputEncodingProvider = { format -> PcmEncoding.PCM_16BIT },
        )

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(inputFormat).build())

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }
}
