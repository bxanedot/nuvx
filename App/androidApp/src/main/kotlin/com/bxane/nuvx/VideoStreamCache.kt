package com.bxane.nuvx

import com.bxane.nuvx.core.VideoQualityPreference
import com.bxane.nuvx.core.VideoStream

internal data class VideoStreamCacheKey(val videoId: String, val quality: VideoQualityPreference)

internal class VideoStreamCache {
    private data class Entry(val stream: VideoStream, val expiresAtMillis: Long)
    private val streams = mutableMapOf<VideoStreamCacheKey, Entry>()

    fun get(key: VideoStreamCacheKey, nowMillis: Long): VideoStream? {
        val entry = streams[key] ?: return null
        if (nowMillis >= entry.expiresAtMillis) {
            streams.remove(key)
            return null
        }
        return entry.stream
    }

    fun put(key: VideoStreamCacheKey, stream: VideoStream, expiresAtMillis: Long) {
        streams[key] = Entry(stream, expiresAtMillis)
    }

    fun clear() = streams.clear()
}
