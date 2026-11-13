// SPDX-License-Identifier: Apache-2.0
package com.bxane.nuvx.playback.audio

import android.media.AudioFormat
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTelemetryHardeningTest {

    @Test
    fun usbEndpointTelemetryDistinguishesPortronicsUac1FromAudioTrackClientFormat() {
        val portronicsSnapshot = AudioOutputStatus.Snapshot(
            deviceName = "Portronics iKonnect C Pro",
            routeKind = AudioRouting.Kind.USB,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT),
            sampleRatesHz = intArrayOf(8000, 48000),
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directSupport = DirectAudioProbe.DirectSupport.NONE,
            actualEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            actualSampleRateHz = 176400,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(portronicsSnapshot)

        assertEquals("PCM16 / 48000 Hz", evaluated.usbEndpointFormat)
        assertTrue(evaluated.directPlaybackRejected)
        assertFalse(evaluated.directPlaybackActual)
        assertEquals(48000, evaluated.systemMixerRateHz)
        assertEquals("PCM24 packed", evaluated.halFormat)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, evaluated.actualEncoding)
        assertEquals(176400, evaluated.actualSampleRateHz)
        assertEquals("Direct playback unavailable for active USB device", evaluated.fallbackDetail)
    }

    @Test
    fun usbEndpointTelemetryDistinguishesExternal24BitDacFromAudioTrackClientFormat() {
        val dac24Snapshot = AudioOutputStatus.Snapshot(
            deviceName = "External 24-bit DAC",
            routeKind = AudioRouting.Kind.USB,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED),
            sampleRatesHz = intArrayOf(44100, 48000),
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directSupport = DirectAudioProbe.DirectSupport.NONE,
            actualEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            actualSampleRateHz = 176400,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(dac24Snapshot)

        assertEquals("PCM24 / 48000 Hz", evaluated.usbEndpointFormat)
        assertTrue(evaluated.directPlaybackRejected)
        assertFalse(evaluated.directPlaybackActual)
        assertEquals(48000, evaluated.systemMixerRateHz)
        assertEquals("PCM24 packed", evaluated.halFormat)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, evaluated.actualEncoding)
        assertEquals(176400, evaluated.actualSampleRateHz)
    }

    @Test
    fun genuineDirectPlaybackReportsBitMatchedAndNullMixer() {
        val directCapableSnapshot = AudioOutputStatus.Snapshot(
            deviceName = "Hi-Res Direct DAC",
            routeKind = AudioRouting.Kind.USB,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED),
            sampleRatesHz = intArrayOf(44100, 48000, 96000, 192000),
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directSupport = DirectAudioProbe.DirectSupport(
                isDirectSupported = true,
                isOffloadSupported = false,
                supportsFloat = false,
                supportsPcm24 = true,
                supportsPcm16 = true,
                description = "Direct 24-bit PCM supported",
            ),
            actualEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
            actualSampleRateHz = 192000,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(directCapableSnapshot)

        assertTrue(evaluated.directPlaybackActual)
        assertFalse(evaluated.directPlaybackRejected)
        assertEquals(TransportType.AUDIO_TRACK_DIRECT, evaluated.transportType)
        assertNull(evaluated.systemMixerRateHz)
        assertNull(evaluated.halFormat)
        assertEquals("PCM24 / 192000 Hz", evaluated.usbEndpointFormat)
    }

    @Test
    fun phoneSpeakerAlwaysSetsHalFormatAndNullUsbEndpoint() {
        val speakerSnapshot = AudioOutputStatus.Snapshot(
            deviceName = "Built-in Speaker",
            routeKind = AudioRouting.Kind.PHONE,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT),
            sampleRatesHz = intArrayOf(48000),
            actualEncoding = AudioFormat.ENCODING_PCM_16BIT,
            actualSampleRateHz = 48000,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(speakerSnapshot)

        assertFalse(evaluated.directPlaybackActual)
        assertFalse(evaluated.directPlaybackRejected)
        assertEquals(48000, evaluated.systemMixerRateHz)
        assertEquals("PCM24 packed", evaluated.halFormat)
        assertNull(evaluated.usbEndpointFormat)
    }

    @Test
    fun staticUsbCapabilityAndActiveAudioTrackRemainSeparate() {
        val chu2Snapshot = AudioOutputStatus.Snapshot(
            deviceName = "Moondrop CHU II DSP",
            routeKind = AudioRouting.Kind.USB,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED),
            sampleRatesHz = intArrayOf(44100, 48000, 88200, 96000),
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directPlaybackSelected = true,
            directSupport = DirectAudioProbe.DirectSupport(
                isDirectSupported = true,
                isOffloadSupported = false,
                supportsFloat = false,
                supportsPcm24 = false,
                supportsPcm16 = true,
                description = "Direct PCM supported: 16-bit @ 176400 Hz",
            ),
            actualEncoding = AudioFormat.ENCODING_PCM_16BIT,
            actualSampleRateHz = 176400,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(chu2Snapshot)

        assertEquals("PCM24 / 96000 Hz", evaluated.usbEndpointFormat)
        assertEquals(176400, evaluated.actualSampleRateHz)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, evaluated.actualEncoding)
        assertTrue("Direct playback must be active at 176.4 kHz", evaluated.directPlaybackActual)
        assertFalse("Direct playback must not be rejected", evaluated.directPlaybackRejected)
        assertNull("System mixer must be bypassed", evaluated.systemMixerRateHz)
    }

    @Test
    fun unknownSampleRateDoesNotRejectDirectPlaybackOnWiredRoute() {
        val pending = AudioOutputStatus.Snapshot(
            deviceName = "Hi-Res Wired DAC",
            routeKind = AudioRouting.Kind.WIRED,
            sampleRatesHz = intArrayOf(44100, 48000, 96000),
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directSupport = DirectAudioProbe.DirectSupport(
                isDirectSupported = true,
                isOffloadSupported = false,
                supportsFloat = false,
                supportsPcm24 = true,
                supportsPcm16 = true,
                description = "Direct PCM supported: 24-bit @ 96000 Hz",
            ),
            actualSampleRateHz = null,
        )

        val evaluated = AudioOutputStatus.evaluateActualPath(pending)

        assertTrue("Direct playback must survive an unmeasured rate", evaluated.directPlaybackActual)
        assertFalse("Direct playback must not be rejected", evaluated.directPlaybackRejected)
    }

    @Test
    fun usbDeviceCapabilityFormattingIsHumanReadable() {
        assertEquals(
            "PCM 24-bit / 96 kHz",
            com.bxane.nuvx.ui.components.formatUsbCapability("PCM24 / 96000 Hz"),
        )
        assertEquals(
            "PCM 16-bit / 48 kHz",
            com.bxane.nuvx.ui.components.formatUsbCapability("PCM16 / 48000 Hz"),
        )
        assertEquals(
            "PCM 24-bit / 192 kHz",
            com.bxane.nuvx.ui.components.formatUsbCapability("PCM24 / 192000 Hz"),
        )
        assertEquals(
            "Float 32-bit / 176.4 kHz",
            com.bxane.nuvx.ui.components.formatUsbCapability("Float32 / 176400 Hz"),
        )
        assertEquals(
            "PCM 24-bit / 44.1 kHz",
            com.bxane.nuvx.ui.components.formatUsbCapability("PCM24 / 44100 Hz"),
        )
    }
}
