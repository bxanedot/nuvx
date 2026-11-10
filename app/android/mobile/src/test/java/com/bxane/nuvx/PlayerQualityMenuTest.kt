package com.bxane.nuvx

import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.QualityUpgrade
import com.bxane.nuvx.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQualityMenuTest {

    private val videoId = "i1o1p_DD6TU"

    @After
    fun tearDown() {
        StreamChoice.forget(videoId)
    }

    @Test
    fun `a track back on YouTube's own stream is offered an upgrade`() {
        StreamChoice.remember(videoId, SourceStream("https://yt/opus"), substituted = false)

        assertTrue(QualityUpgrade.isKnownToBePlayingYouTubesOwn(videoId, rendition = null))
    }

    @Test
    fun `a track playing a substituted copy is not offered an upgrade`() {
        StreamChoice.remember(videoId, SourceStream("https://tidal/flac"), substituted = true)

        assertFalse(QualityUpgrade.isKnownToBePlayingYouTubesOwn(videoId, rendition = null))
    }

    @Test
    fun `an upgraded track keeps its revert row despite the stale YouTube choice`() {
        StreamChoice.remember(videoId, SourceStream("https://yt/opus"), substituted = false)

        assertFalse(QualityUpgrade.isKnownToBePlayingYouTubesOwn(videoId, rendition = "hifi"))
    }

    @Test
    fun `an unresolved cached track of unknown origin keeps its revert row`() {
        assertFalse(QualityUpgrade.isKnownToBePlayingYouTubesOwn(videoId, rendition = null))
    }

    @Test
    fun `a track replayed from YouTube's cached copy is offered an upgrade`() {
        assertTrue(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                videoId,
                rendition = null,
                cachedOrigin = AudioCache.Origin.YOUTUBE,
            ),
        )
    }

    @Test
    fun `a track replayed from a substitute's cached copy keeps its revert row`() {
        listOf(AudioCache.Origin.JIOSAAVN, AudioCache.Origin.OTHER).forEach { origin ->
            assertFalse(
                QualityUpgrade.isKnownToBePlayingYouTubesOwn(videoId, rendition = null, cachedOrigin = origin),
            )
        }
    }

    @Test
    fun `a substitute resolved this session outranks a YouTube cache tag`() {
        StreamChoice.remember(videoId, SourceStream("https://tidal/flac"), substituted = true)

        assertFalse(
            QualityUpgrade.isKnownToBePlayingYouTubesOwn(
                videoId,
                rendition = null,
                cachedOrigin = AudioCache.Origin.YOUTUBE,
            ),
        )
    }
}
