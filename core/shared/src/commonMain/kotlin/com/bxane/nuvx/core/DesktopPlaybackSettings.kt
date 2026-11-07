package com.bxane.nuvx.core

data class DesktopPlaybackSettings(
    val hideVolumeBar: Boolean = false,
    val audioOutput: DesktopAudioOutputSelection = DesktopAudioOutputSelection.DEFAULT,
    val crossfadeSeconds: Int = 0,
) {
    fun save(store: KeyValueStore) {
        store.put(KEY_HIDE_VOLUME_BAR, hideVolumeBar.toString())
        audioOutput.save(store)
        store.put(KEY_CROSSFADE_SECONDS, CrossfadeAudio.normalizeDurationSeconds(crossfadeSeconds).toString())
    }

    companion object {
        private const val KEY_HIDE_VOLUME_BAR = "desktopHideVolumeBar"
        private const val KEY_CROSSFADE_SECONDS = "desktopCrossfadeSeconds"

        fun load(store: KeyValueStore) = DesktopPlaybackSettings(
            hideVolumeBar = store.get(KEY_HIDE_VOLUME_BAR) == "true",
            audioOutput = DesktopAudioOutputSelection.load(store),
            crossfadeSeconds = CrossfadeAudio.normalizeDurationSeconds(store.get(KEY_CROSSFADE_SECONDS)?.toIntOrNull() ?: 0),
        )
    }
}

data class DesktopAudioOutputSelection(val outputName: String? = null, val deviceId: String? = null) {
    val isSystemDefault get() = outputName == null && deviceId == null

    fun save(store: KeyValueStore) {
        store.put(KEY_OUTPUT_NAME, outputName)
        store.put(KEY_DEVICE_ID, deviceId)
    }

    companion object {
        val DEFAULT = DesktopAudioOutputSelection()
        private const val KEY_OUTPUT_NAME = "desktopAudioOutputName"
        private const val KEY_DEVICE_ID = "desktopAudioDeviceId"

        fun load(store: KeyValueStore): DesktopAudioOutputSelection {
            val output = store.get(KEY_OUTPUT_NAME)?.takeIf(String::isNotBlank)
            val device = store.get(KEY_DEVICE_ID)?.takeIf(String::isNotBlank)
            return if (output != null && device != null) DesktopAudioOutputSelection(output, device) else DEFAULT
        }
    }
}

data class DesktopAudioOutputOption(val outputName: String, val deviceId: String, val label: String) {
    val selection get() = DesktopAudioOutputSelection(outputName, deviceId)
}
