package com.bxane.nuvx.ui.tv.audio

import android.media.audiofx.Virtualizer
import com.bxane.nuvx.data.DebugLog as Log

object TvSpatialAudioEngine {
    private const val TAG = "TvSpatialAudioEngine"
    private var virtualizer: Virtualizer? = null
    private var isEnabled = true

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            release()
            virtualizer = Virtualizer(0, audioSessionId).apply {
                if (strengthSupported) {
                    setStrength(1000.toShort())
                }
                enabled = isEnabled
            }
            Log.d(TAG, "Attached 3D Spatial Audio Virtualizer to session $audioSessionId (enabled=$isEnabled)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach Spatial Audio Virtualizer: ${e.message}")
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        try {
            virtualizer?.enabled = enabled
            Log.d(TAG, "Spatial Audio enabled set to: $enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set spatial audio state: ${e.message}")
        }
    }

    fun release() {
        try {
            virtualizer?.release()
            virtualizer = null
        } catch (e: Exception) {
        }
    }
}
