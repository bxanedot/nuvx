package com.bxane.nuvx

import com.bxane.nuvx.core.BackendId
import com.bxane.nuvx.core.VideoQualityPreference
import com.bxane.nuvx.core.VideoStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VideoStreamCacheTest {
    private val videoId = "abcdefghijk"

    private fun stream(height: Int) = VideoStream(
        videoId = videoId,
        audioUrl = "https://rr1---sn.googlevideo.com/audio",
        videoUrl = "https://rr1---sn.googlevideo.com/video-$height",
        headers = emptyMap(),
        width = 1280,
        height = height,
        mimeType = "video/mp4",
        audioMimeType = "audio/mp4",
        codecs = "avc1",
        availableHeights = listOf(360, 720),
        backend = BackendId.INNERTUBEX,
    )

    @Test
    fun cachedVideoIsScopedToIdAndQualityAndExpiresAtItsDeadline() {
        val cache = VideoStreamCache()
        val key = VideoStreamCacheKey(videoId, VideoQualityPreference.DATA_SAVER)
        val lowResolution = stream(360)
        cache.put(key, lowResolution, expiresAtMillis = 1_000)

        assertSame(lowResolution, cache.get(key, nowMillis = 999))
        assertNull(cache.get(VideoStreamCacheKey(videoId, VideoQualityPreference.HIGH_QUALITY), nowMillis = 999))
        assertNull(cache.get(VideoStreamCacheKey("lmnopqrstuv", VideoQualityPreference.DATA_SAVER), nowMillis = 999))
        assertNull(cache.get(key, nowMillis = 1_000))
    }
}
