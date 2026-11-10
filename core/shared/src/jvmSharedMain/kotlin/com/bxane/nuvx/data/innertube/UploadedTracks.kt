package com.bxane.nuvx.data.innertube

import java.util.concurrent.ConcurrentHashMap

object UploadedTracks {

    private const val MAX_REMEMBERED = 20_000

    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun mark(videoId: String) {
        if (videoId.isBlank()) return
        if (ids.size >= MAX_REMEMBERED) ids.clear()
        ids.add(videoId)
    }

    fun contains(videoId: String): Boolean = videoId in ids

    fun clear() {
        ids.clear()
    }
}
