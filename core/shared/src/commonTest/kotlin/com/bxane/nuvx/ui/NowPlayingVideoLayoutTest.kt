package com.bxane.nuvx.ui

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.Track
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingVideoLayoutTest {
    @Test
    fun mediaSwitchRequiresAnActionableTarget() {
        val song = Track("song-id", "Song", pairedVideoId = "paired-video")
        val video = song.copy(mediaKind = MediaKind.VIDEO)
        assertFalse(canSwitchPlaybackKind(song, null))
        assertTrue(canSwitchPlaybackKind(song, video))
        assertTrue(canSwitchPlaybackKind(video, null))
        assertFalse(canSwitchPlaybackKind(video.copy(pairedVideoId = null), null))
        assertFalse(canSwitchPlaybackKind(null, video))
    }
    @Test
    fun fullBleedCoverRemainsAvailableForSongsButNeverHidesVideoSurface() {
        val cover = "https://example.test/cover.jpg"

        assertTrue(shouldUseFullBleedArtwork(true, true, true, Track("song-id", "Song", artworkUrl = cover)))
        assertFalse(shouldUseFullBleedArtwork(true, true, false, Track("song-id", "Song", artworkUrl = cover)))
        assertFalse(shouldUseFullBleedArtwork(true, true, true, Track("video-id", "Video", artworkUrl = cover, mediaKind = MediaKind.VIDEO)))
        assertFalse(shouldUseFullBleedArtwork(true, true, true, Track("video-id", "Video", mediaKind = MediaKind.VIDEO)))
    }
}
