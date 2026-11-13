package com.bxane.nuvx.data.playback

import com.bxane.nuvx.data.settings.PlatformSettings

































object OriginalVersion {

    private const val KEY = "original_version_pins"






    private val pinned = mutableSetOf<String>()


    private val loaded: Boolean by lazy {
        pinned += decode(PlatformSettings.getString(KEY, ""))
        true
    }


    fun pinnedIds(): Set<String> {
        loaded
        return pinned.toSet()
    }


    fun isPinned(videoId: String?): Boolean {
        if (videoId.isNullOrEmpty()) return false
        loaded
        return videoId in pinned
    }


    fun pin(videoId: String) {
        if (videoId.isEmpty()) return
        loaded
        if (pinned.add(videoId)) flush()
    }






    fun unpin(videoId: String) {
        loaded
        if (pinned.remove(videoId)) flush()
    }


    fun forget(videoId: String) = unpin(videoId)

    private fun flush() {
        PlatformSettings.putString(KEY, encode(pinned))
    }

    internal fun encode(ids: Set<String>): String = ids.sorted().joinToString("\n")












    internal fun decode(raw: String): Set<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
