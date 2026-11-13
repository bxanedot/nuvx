package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import kotlin.concurrent.Volatile


object PlaybackCodecCapabilities {
    @Volatile private var appleDolbyAvailable = false
    fun setAppleDolbyAvailable(value: Boolean) { appleDolbyAvailable = value }






    @Volatile var losslessOutput: Boolean = true
        private set
    fun setLosslessOutput(value: Boolean) { losslessOutput = value }








    val allowLosslessSources: Boolean
        get() = AppSettings.effectiveAudioQuality(AppSettings.meteredConnection.value == true) == AudioQuality.LOSSLESS
    fun canRenderDolby(codec: String?, headers: Map<String, String>): Boolean =
        appleDolbyAvailable && AppSettings.dolbyAtmos.value && headers.isEmpty() &&
            codec?.lowercase() in setOf("ac3", "ac-3", "eac3", "e-ac-3", "ec-3", "ec3", "eac3-joc", "ec3-joc", "dolby-atmos")
}
