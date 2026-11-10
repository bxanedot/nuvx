package com.bxane.nuvx.playback

import com.bxane.nuvx.data.settings.OutputPcmMode

internal object AudioOutputPolicy {
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
        bluetoothCodecBitDepth: Int? = null,
    ): Boolean {
        if (routeKind == AudioRouting.Kind.PHONE) return false
        if (routeKind == AudioRouting.Kind.BLUETOOTH && (bluetoothCodecBitDepth ?: 0) > 16) return true
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        return advertisesPcmFloat
    }

    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}
