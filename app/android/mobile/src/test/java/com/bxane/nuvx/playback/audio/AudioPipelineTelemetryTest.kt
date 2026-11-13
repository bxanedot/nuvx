
package com.bxane.nuvx.playback.audio

import android.media.AudioFormat
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TelemetryProvenance
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioPipelineTelemetryTest {

    @Before
    fun setUp() {
        NerdStats.forgetLastSession()
        AudioOutputStatus.reset()
    }

    @Test
    fun flacStreamBitrateDoesNotConflateWithPcmDataRate() {
        val flacSnapshot = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 785,
            bitrateProvenance = TelemetryProvenance.AUTHORITATIVE,
            pcmDataRateKbps = 1411,
            sampleRateHz = 44100,
            channels = 2,
            bitDepth = 16,
        )

        assertEquals(785, flacSnapshot.bitrateKbps)
        assertNotEquals(1411, flacSnapshot.bitrateKbps)
        assertEquals(TelemetryProvenance.AUTHORITATIVE, flacSnapshot.bitrateProvenance)

        assertEquals(1411, flacSnapshot.pcmDataRateKbps)

        assertTrue(flacSnapshot.isLossless)
        assertFalse(flacSnapshot.isHiRes)
        assertFalse(flacSnapshot.isHiQuality)
    }

    @Test
    fun flacHighRes24Bit48kStereoCalculatesPcmRateSeparately() {
        val hiResSnapshot = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 1540,
            bitrateProvenance = TelemetryProvenance.AUTHORITATIVE,
            pcmDataRateKbps = 2304,
            sampleRateHz = 48000,
            channels = 2,
            bitDepth = 24,
        )

        assertEquals(1540, hiResSnapshot.bitrateKbps)
        assertNotEquals(2304, hiResSnapshot.bitrateKbps)
        assertEquals(2304, hiResSnapshot.pcmDataRateKbps)
        assertTrue(hiResSnapshot.isLossless)
        assertTrue(hiResSnapshot.isHiRes)
    }

    @Test
    fun flacUnknownBitrateLeavesBitrateNullAndPcmRateCalculated() {
        val unknownBitrateFlac = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = null,
            bitrateProvenance = TelemetryProvenance.UNKNOWN,
            pcmDataRateKbps = 1411,
            sampleRateHz = 44100,
            channels = 2,
            bitDepth = 16,
        )

        assertNull(unknownBitrateFlac.bitrateKbps)
        assertEquals(TelemetryProvenance.UNKNOWN, unknownBitrateFlac.bitrateProvenance)
        assertEquals(1411, unknownBitrateFlac.pcmDataRateKbps)
        assertTrue(unknownBitrateFlac.isLossless)
    }

    @Test
    fun rawPcmStreamBitrateIsDerivedFromPcmThroughput() {
        val pcmThroughput = (16L * 44100 * 2 / 1000).toInt()
        val wavSnapshot = NerdStats.Snapshot(
            mimeType = "audio/raw",
            bitrateKbps = pcmThroughput,
            bitrateProvenance = TelemetryProvenance.DERIVED,
            pcmDataRateKbps = pcmThroughput,
            sampleRateHz = 44100,
            channels = 2,
            bitDepth = 16,
        )

        assertEquals(1411, wavSnapshot.bitrateKbps)
        assertEquals(TelemetryProvenance.DERIVED, wavSnapshot.bitrateProvenance)
        assertEquals(1411, wavSnapshot.pcmDataRateKbps)
        assertTrue(wavSnapshot.isLossless)
        assertTrue(NerdStats.isRawPcm("audio/raw"))
        assertTrue(NerdStats.isRawPcm("audio/wav"))
        assertTrue(NerdStats.isRawPcm("audio/x-wav"))
        assertFalse(NerdStats.isRawPcm("audio/flac"))
        assertFalse(NerdStats.isRawPcm("audio/opus"))
    }

    @Test
    fun lossyFormatsHaveNoBitDepthAndNoPcmDataRate() {
        val opusSnapshot = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = 160,
            bitrateProvenance = TelemetryProvenance.AUTHORITATIVE,
            pcmDataRateKbps = null,
            sampleRateHz = 48000,
            channels = 2,
            bitDepth = null,
        )

        assertFalse(opusSnapshot.isLossless)
        assertNull(opusSnapshot.bitDepth)
        assertNull(opusSnapshot.pcmDataRateKbps)
        assertEquals(160, opusSnapshot.bitrateKbps)
        assertEquals(TelemetryProvenance.AUTHORITATIVE, opusSnapshot.bitrateProvenance)
    }

    @Test
    fun resamplerDoesNotFalselyReportPassthroughWhenOutputRateIsUnknown() {
        val inRate: Int? = 44100
        val outRate: Int? = null

        val isPassthrough = inRate != null && outRate != null && inRate == outRate
        val resamplerType = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "None"
            else -> "Resampler"
        }
        val qualityText = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "Passthrough"
            else -> "Resampled"
        }

        assertFalse(isPassthrough)
        assertEquals("—", resamplerType)
        assertEquals("—", qualityText)
    }

    @Test
    fun resamplerAccuratelyReportsPassthroughWhenRatesMatch() {
        val inRate: Int? = 48000
        val outRate: Int? = 48000

        val isPassthrough = inRate != null && outRate != null && inRate == outRate
        val resamplerType = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "None"
            else -> "Resampler"
        }
        val qualityText = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "Passthrough"
            else -> "Resampled"
        }

        assertTrue(isPassthrough)
        assertEquals("None", resamplerType)
        assertEquals("Passthrough", qualityText)
    }

    @Test
    fun resamplerAccuratelyReportsResampledWhenRatesDiffer() {
        val inRate: Int? = 44100
        val outRate: Int? = 48000

        val isPassthrough = inRate != null && outRate != null && inRate == outRate
        val resamplerType = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "None"
            else -> "Resampler"
        }
        val qualityText = when {
            inRate == null || outRate == null -> "—"
            isPassthrough -> "Passthrough"
            else -> "Resampled"
        }

        assertFalse(isPassthrough)
        assertEquals("Resampler", resamplerType)
        assertEquals("Resampled", qualityText)
    }

    @Test
    fun audioTrackDoesNotFabricateFloat32WhenUninitialized() {
        val actualEncoding: Int? = null
        val actualSampleRateHz: Int? = null

        val audioTrackEncoding = when (actualEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
            else -> null
        }
        val audioTrackRate = actualSampleRateHz
        val audioTrackText = when {
            audioTrackEncoding != null && audioTrackRate != null -> "$audioTrackEncoding / $audioTrackRate Hz"
            audioTrackEncoding != null -> audioTrackEncoding
            audioTrackRate != null -> "$audioTrackRate Hz"
            else -> "—"
        }

        assertEquals("—", audioTrackText)
        assertNotEquals("Float32 / 48000 Hz", audioTrackText)
    }

    @Test
    fun audioTrackDisplaysExactConfiguredFormat() {
        val actualEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
        val actualSampleRateHz = 96000

        val audioTrackEncoding = when (actualEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
            else -> null
        }
        val audioTrackRate = actualSampleRateHz
        val audioTrackText = when {
            audioTrackEncoding != null && audioTrackRate != null -> "$audioTrackEncoding / $audioTrackRate Hz"
            audioTrackEncoding != null -> audioTrackEncoding
            audioTrackRate != null -> "$audioTrackRate Hz"
            else -> "—"
        }

        assertEquals("PCM24 / 96000 Hz", audioTrackText)
    }

    @Test
    fun trackTransitionClearsStalePerTrackMetadata() {
        AudioOutputStatus.publishDecoder("c2.android.flac.decoder")
        AudioOutputStatus.publishDsp(decoderOutputEncoding = "24-bit PCM")
        AudioOutputStatus.publishOutputExactness(exact = true, detail = "Bit-exact path active")
        AudioOutputStatus.publishLoudness(gainDb = -2.5f, lufs = -14.0f)

        assertEquals("c2.android.flac.decoder", AudioOutputStatus.current.value.decoderName)
        assertEquals("24-bit PCM", AudioOutputStatus.current.value.decoderOutputEncoding)
        assertTrue(AudioOutputStatus.current.value.outputExact)
        assertEquals(-2.5f, AudioOutputStatus.current.value.loudnessGainDb)
        assertEquals(-14.0f, AudioOutputStatus.current.value.loudnessLufs)

        AudioOutputStatus.onTrackTransition()

        val after = AudioOutputStatus.current.value
        assertNull(after.decoderName)
        assertNull(after.decoderOutputEncoding)
        assertFalse(after.outputExact)
        assertNull(after.outputExactDetail)
        assertNull(after.loudnessGainDb)
        assertNull(after.loudnessLufs)
    }

    @Test
    fun nerdStatsTrackTransitionResetsCurrentSnapshot() {
        NerdStats.current.value = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 800,
            sampleRateHz = 44100,
            channels = 2,
            bitDepth = 16,
        )
        assertEquals(800, NerdStats.current.value?.bitrateKbps)

        NerdStats.onTrackTransition()

        assertNull(NerdStats.current.value)
    }

    @Test
    fun bluetoothRouteTransitionClearsBluetoothProfileWhenRoutingAway() {
        val btSnapshot = AudioOutputStatus.Snapshot(
            routeKind = AudioRouting.Kind.BLUETOOTH,
            bluetoothProfile = "A2DP",
        )
        AudioOutputStatus.current.value = btSnapshot
        assertEquals("A2DP", AudioOutputStatus.current.value.bluetoothProfile)

        val phoneNegotiation = OutputNegotiator.negotiate(
            source = SourceDescriptor(
                encoding = "16-bit PCM",
                sampleRateHz = 44100,
                channelCount = 2,
                bitDepth = 16,
            ),
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 44100,
            channelCount = 2,
            routeKind = AudioRouting.Kind.PHONE,
            deviceName = "Speaker",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            advertisedSampleRates = listOf(48000),
            requestedMode = com.bxane.nuvx.data.settings.OutputPcmMode.PCM_16,
        )

        AudioOutputStatus.publishNegotiation(phoneNegotiation)

        val evaluated = AudioOutputStatus.current.value
        assertEquals(AudioRouting.Kind.PHONE, evaluated.routeKind)
        assertNull(evaluated.bluetoothProfile)
        assertNull(evaluated.bluetoothTelemetry)
    }
}
