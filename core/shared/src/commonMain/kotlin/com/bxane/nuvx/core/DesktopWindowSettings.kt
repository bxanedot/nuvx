package com.bxane.nuvx.core

data class DesktopWindowSettings(
    val showTrayIcon: Boolean = false,
    val keepPlayingWhenClosed: Boolean = false,
) {
    fun normalized() = copy(keepPlayingWhenClosed = keepPlayingWhenClosed && showTrayIcon)

    fun save(store: KeyValueStore) {
        val value = normalized()
        store.put(KEY_TRAY_ICON, value.showTrayIcon.toString())
        store.put(KEY_KEEP_PLAYING, value.keepPlayingWhenClosed.toString())
    }

    companion object {
        private const val KEY_TRAY_ICON = "desktopTrayIcon"
        private const val KEY_KEEP_PLAYING = "desktopKeepPlayingWhenClosed"

        fun load(store: KeyValueStore) = DesktopWindowSettings(
            showTrayIcon = store.get(KEY_TRAY_ICON) == "true",
            keepPlayingWhenClosed = store.get(KEY_KEEP_PLAYING) == "true",
        ).normalized()
    }
}
