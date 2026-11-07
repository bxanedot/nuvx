package com.bxane.nuvx

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureEligibilityTest {
    @Test fun onlySupportedPlayingPhoneVideoCanEnterPip() {
        val video = Track("video", "Video", mediaKind = MediaKind.VIDEO)
        val playingVideo = PlayerState(queue = listOf(video), index = 0, isPlaying = true)
        val playingSong = playingVideo.copy(queue = listOf(video.copy(mediaKind = MediaKind.SONG)))
        val pausedVideo = playingVideo.copy(isPlaying = false)

        assertTrue(playingVideo.canEnterPictureInPicture(true, watch = false, tv = false))
        assertFalse(playingSong.canEnterPictureInPicture(true, watch = false, tv = false))
        assertFalse(pausedVideo.canEnterPictureInPicture(true, watch = false, tv = false))
        assertFalse(playingVideo.canEnterPictureInPicture(false, watch = false, tv = false))
        assertFalse(playingVideo.canEnterPictureInPicture(true, watch = true, tv = false))
        assertFalse(playingVideo.canEnterPictureInPicture(true, watch = false, tv = true))
    }
}
