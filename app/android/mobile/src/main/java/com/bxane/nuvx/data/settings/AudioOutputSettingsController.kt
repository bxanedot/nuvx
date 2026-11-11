package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

internal class AudioOutputSettingsController {
    private lateinit var prefs: SharedPreferences

    val excorFallback = MutableStateFlow(true)
    val outputPcmMode = MutableStateFlow(OutputPcmMode.PCM_16)
    val preferUsbDac = MutableStateFlow(false)
    val bitPerfectMode = MutableStateFlow(BitPerfectMode.OFF)
    val usbAllowFixedVolume = MutableStateFlow(false)
    val convertForLimitedDac = MutableStateFlow(false)
    val loudnessNormalization = MutableStateFlow(true)
    val loudnessOffOnSpeaker = MutableStateFlow(true)
    val dolbyAtmos = MutableStateFlow(true)
    val directSourceStream = MutableStateFlow(false)
    val spatialAudio = MutableStateFlow(true)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        excorFallback.value = prefs.getBoolean(KEY_EXCOR_FALLBACK, true)
        outputPcmMode.value = runCatching {
            OutputPcmMode.valueOf(
                prefs.getString(KEY_OUTPUT_PCM_MODE, OutputPcmMode.PCM_16.name)
                    ?: OutputPcmMode.PCM_16.name,
            )
        }.getOrDefault(OutputPcmMode.PCM_16)
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        bitPerfectMode.value = runCatching {
            BitPerfectMode.valueOf(prefs.getString(KEY_BIT_PERFECT_MODE, null) ?: BitPerfectMode.OFF.name)
        }.getOrDefault(BitPerfectMode.OFF)
        usbAllowFixedVolume.value = prefs.getBoolean(KEY_USB_ALLOW_FIXED_VOLUME, false)
        convertForLimitedDac.value = prefs.getBoolean(KEY_CONVERT_FOR_LIMITED_DAC, false)
        loudnessNormalization.value = prefs.getBoolean(KEY_LOUDNESS_NORMALIZATION, true)
        loudnessOffOnSpeaker.value = prefs.getBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, true)
        dolbyAtmos.value = prefs.getBoolean(KEY_DOLBY_ATMOS, true)
        directSourceStream.value = prefs.getBoolean(KEY_DIRECT_SOURCE_STREAM, false)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, true)
    }

    fun setExcorFallback(value: Boolean) {
        excorFallback.value = value
        prefs.edit().putBoolean(KEY_EXCOR_FALLBACK, value).apply()
    }

    fun setOutputPcmMode(value: OutputPcmMode) {
        outputPcmMode.value = value
        prefs.edit().putString(KEY_OUTPUT_PCM_MODE, value.name).apply()
    }

    fun setPreferUsbDac(value: Boolean) {
        preferUsbDac.value = value
        prefs.edit().putBoolean(KEY_PREFER_USB_DAC, value).apply()
    }

    fun setBitPerfectMode(value: BitPerfectMode) {
        bitPerfectMode.value = value
        prefs.edit().putString(KEY_BIT_PERFECT_MODE, value.name).apply()
    }

    fun setUsbAllowFixedVolume(value: Boolean) {
        usbAllowFixedVolume.value = value
        prefs.edit().putBoolean(KEY_USB_ALLOW_FIXED_VOLUME, value).apply()
    }

    fun setConvertForLimitedDac(value: Boolean) {
        convertForLimitedDac.value = value
        prefs.edit().putBoolean(KEY_CONVERT_FOR_LIMITED_DAC, value).apply()
    }

    fun setLoudnessOffOnSpeaker(value: Boolean) {
        loudnessOffOnSpeaker.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, value).apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        loudnessNormalization.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_NORMALIZATION, value).apply()
    }

    fun setDolbyAtmos(value: Boolean) {
        dolbyAtmos.value = value
        prefs.edit().putBoolean(KEY_DOLBY_ATMOS, value).apply()
    }

    fun setDirectSourceStream(value: Boolean) {
        directSourceStream.value = value
        prefs.edit().putBoolean(KEY_DIRECT_SOURCE_STREAM, value).apply()
    }

    fun setSpatialAudio(value: Boolean) {
        spatialAudio.value = value
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply()
    }

    private companion object {
        const val KEY_EXCOR_FALLBACK = "excor_fallback"
        const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
        const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
        const val KEY_BIT_PERFECT_MODE = "bit_perfect_mode"
        const val KEY_USB_ALLOW_FIXED_VOLUME = "usb_allow_fixed_volume"
        const val KEY_CONVERT_FOR_LIMITED_DAC = "convert_for_limited_dac"
        const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
        const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
        const val KEY_DOLBY_ATMOS = "dolby_atmos"
        const val KEY_DIRECT_SOURCE_STREAM = "direct_source_stream"
        const val KEY_SPATIAL_AUDIO = "spatial_audio"
    }
}
