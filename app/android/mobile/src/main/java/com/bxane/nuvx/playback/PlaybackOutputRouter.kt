package com.bxane.nuvx.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.audio.DirectAudioProbe
import com.bxane.nuvx.playback.audio.OutputNegotiator
import com.bxane.nuvx.playback.audio.PcmEncoding
import com.bxane.nuvx.playback.audio.SourceDescriptor
import com.bxane.nuvx.playback.audio.bluetooth.BluetoothAudioTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
internal class PlaybackOutputRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val spare: () -> ExoPlayer?,
    private val isTransitioning: () -> Boolean,
    private val detachPlayer: (ExoPlayer) -> Unit,
    private val buildAndSetPlayers: () -> Pair<ExoPlayer, ExoPlayer>,
    private val applySettings: (ExoPlayer) -> Unit,
    private val applyProcessors: () -> Unit,
    private val setupLoudness: (String?) -> Unit,
    private val attachPlayer: (ExoPlayer) -> Unit,
) {
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val bluetoothTracker by lazy { BluetoothAudioTracker(context) }
    var currentAudioInputFormat: Format? = null
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            AudioRouting.forget()
            requestOutputReconfiguration()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.id == AudioRouting.selectedId.value }) {
                AudioRouting.forget()
            }
            requestOutputReconfiguration()
        }
    }

    var configuredFloatOutput = false
        private set

    private var outputReconfigureJob: Job? = null

    fun startBluetoothTracking() {
        bluetoothTracker.start()
        scope.launch {
            bluetoothTracker.telemetry
                .map { it.copy(lastUpdatedMs = 0L) }
                .distinctUntilChanged()
                .collect { requestOutputReconfiguration() }
        }
    }

    fun configureInitialOutput() {
        configuredFloatOutput = shouldEnableFloatOutput()
    }

    fun registerDeviceCallback() {
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
    }

    fun stop() {
        bluetoothTracker.stop()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
    }

    private fun lastReportedSampleRateHz(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.sampleRateHz?.takeIf { it > 0 }
            ?: status.actualSampleRateHz?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun lastReportedChannelCount(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.channels?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.channelCount?.takeIf { it > 0 }
    }

    fun applyOutputRoute() {
        val manager = audioManager ?: return
        val usb = preferredUsbDevice()
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: usb.takeIf { AppSettings.preferUsbDac.value }
        player()?.setPreferredAudioDevice(preferred)
        spare()?.setPreferredAudioDevice(preferred)

        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        val format = currentAudioInputFormat
        val measured by lazy(LazyThreadSafetyMode.NONE) { format?.measure() }
        val sampleRate = format?.sampleRate?.takeIf { it > 0 }
            ?: measured?.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
        val channels = format?.channelCount?.takeIf { it > 0 }
            ?: measured?.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val directSupport = if (sampleRate != null) {
            DirectAudioProbe.probeDirectSupport(
                audioManager = manager,
                sampleRateHz = sampleRate,
                channelCount = channels,
                activeDevice = activeDevice,
            )
        } else {
            DirectAudioProbe.DirectSupport.NONE
        }
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = activeDevice,
            floatEnabled = shouldEnableFloatOutput(),
            routeKind = routeKind,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            systemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        if (format != null) {
            resolvePreferredOutputEncoding(format)
        }
    }

    fun resolvePreferredOutputEncoding(format: Format): PcmEncoding? {
        val manager = audioManager ?: return null
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        if (routeKind == AudioRouting.Kind.PHONE) {
            return PcmEncoding.PCM_16BIT
        }

        val inputFormat = currentAudioInputFormat ?: format
        val measuredFormat = format.measure()
        val measuredInput by lazy(LazyThreadSafetyMode.NONE) {
            if (inputFormat === format) measuredFormat else inputFormat.measure()
        }

        val sampleRate = format.sampleRate.takeIf { it > 0 }
            ?: inputFormat.sampleRate.takeIf { it > 0 }
            ?: measuredFormat.sampleRateHz?.takeIf { it > 0 }
            ?: measuredInput.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
            ?: return null

        val channels = format.channelCount.takeIf { it > 0 }
            ?: inputFormat.channelCount.takeIf { it > 0 }
            ?: measuredFormat.channels?.takeIf { it > 0 }
            ?: measuredInput.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val bitDepth = measuredFormat.bitDepth
            ?: measuredInput.bitDepth
            ?: NerdStats.current.value?.bitDepth ?: when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_FLOAT -> 32
            else -> when (format.pcmEncoding) {
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
        }

        val sourceEncoding = if (inputFormat.sampleMimeType != null && inputFormat.sampleMimeType != "audio/raw") {
            inputFormat.sampleMimeType!!
        } else {
            format.sampleMimeType ?: "audio/raw"
        }

        val source = SourceDescriptor(
            encoding = sourceEncoding,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitDepth = bitDepth,
        )

        val directSupport = DirectAudioProbe.probeDirectSupport(
            audioManager = manager,
            sampleRateHz = sampleRate,
            channelCount = channels,
            activeDevice = activeDevice,
        )
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        val advertisedEncodings = activeDevice?.encodings?.toList() ?: emptyList()
        val advertisedSampleRates = activeDevice?.sampleRates?.toList() ?: emptyList()

        val negotiation = OutputNegotiator.negotiate(
            source = source,
            decoderName = AudioOutputStatus.current.value.decoderName,
            sampleRateHz = sampleRate,
            channelCount = channels,
            routeKind = routeKind,
            deviceName = activeDevice?.productName?.toString()?.ifBlank { null } ?: "System default",
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = AppSettings.outputPcmMode.value,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            delegateSupportsFloat = shouldEnableFloatOutput(),
            delegateSupportsPcm24 = false,
            knownSystemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        AudioOutputStatus.publishNegotiation(negotiation)

        return negotiation.output.encoding
    }

    fun requestOutputReconfiguration(rebuild: Boolean = false) {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            while (isTransitioning()) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput && !rebuild) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player() ?: return
        val oldSpare = spare() ?: return
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffleMode = oldActive.shuffleModeEnabled

        oldActive.playWhenReady = false
        detachPlayer(oldActive)

        configuredFloatOutput = enableFloat
        val (newActive, newSpare) = buildAndSetPlayers()
        newSpare.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId
        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffleMode
        applyProcessors()
        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(items.indices), position)
            setupLoudness(items.getOrNull(index.coerceIn(items.indices))?.mediaId)
        }
        attachPlayer(newActive)
        applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        TrackLog.i(
            "AUDIO_OUT",
            "live output switch: ${if (enableFloat) "PCM_FLOAT" else "PCM_16BIT"}",
            about = newActive.currentMediaItem?.mediaId,
        )
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    private fun resolveActiveOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val infos = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        return preferred ?: AudioRouting.activeOf(infos, null)
    }

    private fun resolveActiveRouteKind(device: AudioDeviceInfo?): AudioRouting.Kind {
        if (device == null) return AudioRouting.Kind.PHONE
        return AudioRouting.kindOf(device.type) ?: AudioRouting.Kind.PHONE
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val advertisesFloat = activeDevice?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        val directFloatSupported = audioManager?.let { mgr ->
            DirectAudioProbe.probeDirectSupport(mgr, 48000, 2, activeDevice).supportsFloat
        } ?: false
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesFloat || directFloatSupported,
            bluetoothCodecBitDepth = bluetoothCodecBitDepth(routeKind),
        )
    }

    private fun bluetoothCodecBitDepth(routeKind: AudioRouting.Kind): Int? {
        if (routeKind != AudioRouting.Kind.BLUETOOTH) return null
        val telemetry = bluetoothTracker.telemetry.value
        return telemetry.bitDepth.takeIf { telemetry.isConnected && telemetry.isAuthoritative }
    }
}

private const val STREAM_INFO_BYTES = 34

internal class MeasuredAudioFormat(
    val sampleRateHz: Int?,
    val channels: Int?,
    val bitDepth: Int?,
) {
    val pcmBitrateKbps: Int?
        get() {
            val depth = bitDepth ?: return null
            val rate = sampleRateHz ?: return null
            val ch = channels ?: return null
            return (depth.toLong() * rate * ch / 1000).toInt().takeIf { it > 0 }
        }
}

internal fun Format.measure(): MeasuredAudioFormat {
    val streamInfo = flacStreamInfo()
    val rendererDepth = if (pcmEncoding == C.ENCODING_PCM_FLOAT) null else bitDepthOf(pcmEncoding)
    return MeasuredAudioFormat(
        sampleRateHz = sampleRate.takeIf { it > 0 } ?: streamInfo?.sampleRateHz,
        channels = channelCount.takeIf { it > 0 } ?: streamInfo?.channels,
        bitDepth = rendererDepth ?: streamInfo?.bitDepth,
    )
}

private class FlacStreamInfo(val sampleRateHz: Int, val channels: Int, val bitDepth: Int)

private fun Format.flacStreamInfo(): FlacStreamInfo? {
    if (sampleMimeType != MimeTypes.AUDIO_FLAC) return null
    val data = initializationData.firstOrNull() ?: return null
    fun byteAt(i: Int) = data[i].toInt() and 0xFF
    fun headerAt(i: Int) = data.size > i + 3 &&
        (byteAt(i) and 0x7F) == 0 &&
        byteAt(i + 1) == 0 && byteAt(i + 2) == 0 && byteAt(i + 3) == STREAM_INFO_BYTES
    val body = when {
        data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" ->
            if (headerAt(4)) 8 else 4
        headerAt(0) -> 4
        else -> 0
    }
    if (data.size < body + STREAM_INFO_BYTES) return null
    fun at(i: Int) = byteAt(body + i)
    val sampleRate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
    val channels = ((at(12) shr 1) and 0x07) + 1
    val bitDepth = (((at(12) and 0x01) shl 4) or (at(13) shr 4)) + 1
    return FlacStreamInfo(sampleRate, channels, bitDepth).takeIf { sampleRate > 0 }
}

private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
    C.ENCODING_PCM_FLOAT -> 32
    else -> null
}
