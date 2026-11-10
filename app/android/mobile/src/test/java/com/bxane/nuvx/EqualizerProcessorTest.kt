package com.bxane.nuvx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.bxane.nuvx.playback.EqLayout
import com.bxane.nuvx.playback.EqualizerPreset
import com.bxane.nuvx.playback.EqualizerProcessor
import com.bxane.nuvx.playback.PeakLimiter
import com.bxane.nuvx.playback.manualCurve
import com.bxane.nuvx.playback.toneCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerProcessorTest {

    @Test
    fun `a band lifts its own centre by the decibels it was given`() {
        for (band in 1 until EqLayout.MANUAL_COUNT - 1) {
            val gain = measureBand(band, BOOST_DB)
            assertEquals("band $band at +$BOOST_DB dB", BOOST_DB.toDouble(), gain, TOLERANCE_DB)
        }
    }

    @Test
    fun `a band cuts its own centre by the decibels it was given`() {
        for (band in 1 until EqLayout.MANUAL_COUNT - 1) {
            val gain = measureBand(band, -BOOST_DB)
            assertEquals("band $band at -$BOOST_DB dB", -BOOST_DB.toDouble(), gain, TOLERANCE_DB)
        }
    }

    @Test
    fun `the end bands are shelves, half way up at their corner and all the way past it`() {
        val low = EqLayout.MANUAL_BANDS_HZ[0].toDouble()
        assertEquals("60 Hz corner", BOOST_DB / 2.0, measureBand(0, BOOST_DB, low), TOLERANCE_DB)
        assertEquals("20 Hz shelf", BOOST_DB.toDouble(), measureBand(0, BOOST_DB, 20.0), TOLERANCE_DB)

        val high = EqLayout.MANUAL_BANDS_HZ.last().toDouble()
        val top = EqLayout.MANUAL_COUNT - 1
        assertEquals("14 kHz corner", BOOST_DB / 2.0, measureBand(top, BOOST_DB, high), TOLERANCE_DB)
    }

    @Test
    fun `the tone pad tilts the spectrum without moving its middle`() {
        val bright = toneCurve(x = EqLayout.TONE_STEPS, y = 0, focused = false)
        val corner = EqLayout.TONE_STEPS * EqLayout.TONE_DB_PER_STEP

        val processor = EqualizerProcessor()
        processor.setTuning(enabled = true, curve = bright, balance = 0f)
        val lowEnd = measure(processor, 60.0) - bright.preampDb
        val highEnd = measure(processor, 12_000.0) - bright.preampDb

        assertEquals("bass cut at x=+5", -corner.toDouble(), lowEnd, TILT_TOLERANCE_DB)
        assertEquals("treble lifted at x=+5", corner.toDouble(), highEnd, TILT_TOLERANCE_DB)
    }

    @Test
    fun `the tone pad's contour moves the mids and leaves the ends alone`() {
        val forward = toneCurve(x = 0, y = EqLayout.TONE_STEPS, focused = true)
        val processor = EqualizerProcessor()
        processor.setTuning(enabled = true, curve = forward, balance = 0f)

        val mids = measure(processor, 1_000.0) - forward.preampDb
        val bass = measure(processor, 60.0) - forward.preampDb

        val expected = (EqLayout.TONE_STEPS * EqLayout.TONE_DB_PER_STEP).toDouble()
        assertEquals("1 kHz lifted", expected, mids, TOLERANCE_DB)
        assertTrue("60 Hz should be left alone, was $bass dB", abs(bass) < 0.5)
    }

    @Test
    fun `focused is narrower than broad at the same setting`() {
        val steps = EqLayout.TONE_STEPS
        val broad = EqualizerProcessor().also {
            it.setTuning(true, toneCurve(0, steps, focused = false), 0f)
        }
        val focused = EqualizerProcessor().also {
            it.setTuning(true, toneCurve(0, steps, focused = true), 0f)
        }
        val broadCurve = toneCurve(0, steps, focused = false)
        val focusedCurve = toneCurve(0, steps, focused = true)

        val broadSkirt = measure(broad, 500.0) - broadCurve.preampDb
        val focusedSkirt = measure(focused, 500.0) - focusedCurve.preampDb
        assertTrue(
            "broad ($broadSkirt dB) should reach further than focused ($focusedSkirt dB)",
            broadSkirt > focusedSkirt + 1.5,
        )
    }

    @Test
    fun `balance silences the side it is pushed away from`() {
        val processor = EqualizerProcessor()
        processor.setTuning(enabled = true, curve = manualCurve(EqualizerPreset.FLAT.bands), balance = -1f)
        val output = run(processor, stereoTone(1_000.0))

        val right = (1 until output.size step 2).maxOf { abs(output[it].toInt()) }
        val left = (0 until output.size step 2).maxOf { abs(output[it].toInt()) }
        assertEquals("hard left should mute the right channel", 0, right)
        assertTrue("hard left should leave the left channel alone, peaked at $left", left > 7_000)
    }

    @Test
    fun `boosting every band at once does not clip`() {
        val processor = EqualizerProcessor()
        val bands = List(EqLayout.MANUAL_COUNT) { EqLayout.MANUAL_RANGE_DB }
        processor.setTuning(enabled = true, curve = manualCurve(bands), balance = 0f)

        val input = stereoTone(400.0, amplitude = 24_000.0)
        val output = run(processor, input)
        val peak = output.maxOf { abs(it.toInt()) }
        val ceiling = PeakLimiter.CEILING * 32_768
        assertTrue("output peaked at $peak, past the limiter's $ceiling", peak <= ceiling + 1)
    }

    @Test
    fun `the make-up takes back half of the boost`() {
        val bands = MutableList(EqLayout.MANUAL_COUNT) { 0f }
        bands[3] = EqLayout.MANUAL_RANGE_DB
        assertEquals(-EqLayout.MANUAL_RANGE_DB / 2, manualCurve(bands).preampDb, 0.1f)
    }

    @Test
    fun `a switched-off equaliser hands the signal back untouched`() {
        val processor = EqualizerProcessor()
        processor.setTuning(enabled = false, curve = manualCurve(EqualizerPreset.ROCK.bands), balance = 0.5f)
        val input = stereoTone(1_000.0)
        assertArrayEqualsShort(input, run(processor, input))
    }

    @Test
    fun `once the switch-off has glided down the passthrough is exact again`() {
        val processor = EqualizerProcessor()
        processor.setTuning(enabled = true, curve = manualCurve(EqualizerPreset.BASS_BOOST.bands), balance = 0f)
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val chunk = stereoTone(1_000.0, frames = 4_096)
        drain(processor, chunk)
        processor.setTuning(enabled = false, curve = manualCurve(EqualizerPreset.FLAT.bands), balance = 0f)
        repeat(12) { drain(processor, chunk) }
        assertArrayEqualsShort(chunk, drain(processor, chunk))
    }

    @Test
    fun `every preset covers every band`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            assertEquals(
                "${preset.name} should have one value per band",
                EqLayout.MANUAL_COUNT,
                preset.bandsDb.size,
            )
        }
    }

    @Test
    fun `a preset's own bands name it, and anything else is custom`() {
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.matching(EqualizerPreset.ROCK.bands))
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.matching(List(EqLayout.MANUAL_COUNT) { 7f }))
    }

    @Test
    fun `the make-up attenuation never becomes a boost`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            val preamp = manualCurve(preset.bands).preampDb
            assertTrue("${preset.name} asked for $preamp dB of make-up", preamp <= 0.001f)
        }
    }

    private fun measureBand(
        band: Int,
        gainDb: Float,
        atHz: Double = EqLayout.MANUAL_BANDS_HZ[band].toDouble(),
    ): Double {
        val bands = MutableList(EqLayout.MANUAL_COUNT) { 0f }
        bands[band] = gainDb
        val curve = manualCurve(bands)
        val processor = EqualizerProcessor()
        processor.setTuning(enabled = true, curve = curve, balance = 0f)
        return measure(processor, atHz) - curve.preampDb
    }

    private fun measure(processor: EqualizerProcessor, hz: Double): Double {
        val input = stereoTone(hz)
        val output = run(processor, input)
        return 20 * log10(amplitudeAt(output, hz) / amplitudeAt(input, hz))
    }

    private fun amplitudeAt(samples: ShortArray, hz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        var count = 0
        for (frame in SETTLE_FRAMES until samples.size / 2) {
            val angle = 2 * PI * hz * frame / SAMPLE_RATE
            val value = samples[frame * 2].toDouble()
            real += value * cos(angle)
            imaginary += value * sin(angle)
            count++
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / count
    }

    private fun stereoTone(
        hz: Double,
        amplitude: Double = AMPLITUDE,
        frames: Int = TONE_FRAMES,
    ): ShortArray {
        val samples = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2 * PI * hz * frame / SAMPLE_RATE)).toInt().toShort()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        return samples
    }

    private fun run(processor: EqualizerProcessor, input: ShortArray): ShortArray {
        val format = AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        val output = ShortArray(input.size)
        var written = 0
        var offset = 0
        while (offset < input.size) {
            val length = minOf(CHUNK_SAMPLES, input.size - offset)
            val chunk = drain(processor, input.copyOfRange(offset, offset + length))
            chunk.copyInto(output, written)
            written += chunk.size
            offset += length
        }
        return output
    }

    private fun drain(processor: EqualizerProcessor, input: ShortArray): ShortArray {
        processor.queueInput(input.toBuffer())
        val out = processor.output
        val result = ShortArray(out.remaining() / 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().get(result)
        return result
    }

    private fun ShortArray.toBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder())
        forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals("sample count", expected.size, actual.size)
        for (index in expected.indices) {
            if (expected[index] != actual[index]) {
                throw AssertionError(
                    "sample $index: expected ${expected[index]} but was ${actual[index]}",
                )
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000

        const val TONE_FRAMES = SAMPLE_RATE * 2

        const val SETTLE_FRAMES = SAMPLE_RATE / 2

        const val CHUNK_SAMPLES = 4_096 * 2

        const val AMPLITUDE = 8_000.0

        const val BOOST_DB = 6f

        const val TOLERANCE_DB = 0.4

        const val TILT_TOLERANCE_DB = 0.8
    }
}
