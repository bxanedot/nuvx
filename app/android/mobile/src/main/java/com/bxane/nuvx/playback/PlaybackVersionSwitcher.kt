package com.bxane.nuvx.playback

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.media3.session.MediaController
import com.bxane.nuvx.R
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.smart.VersionAudioAligner

internal class PlaybackVersionSwitcher(
    private val mediaController: MediaController?,
    private val versionContext: Context,
    private val showQueueNotice: (String) -> Unit,
    private val missingVersions: SnapshotStateList<String>,
    private val switchingAudioVersion: MutableState<Boolean>,
    private val keepVideoId: MutableState<String?>,
    private val convertedFromVideo: MutableState<Song?>,
    private val convertedAudioId: MutableState<String?>,
    private val convertedFromAudio: MutableState<Song?>,
    private val convertedVideoId: MutableState<String?>,
    private val optimisticVersionSong: MutableState<Song?>,
    private val activeRadioSeed: MutableState<Pair<String, String>?>,
) {
    fun onCurrentTrackChanged(videoId: String?) {
        if (optimisticVersionSong.value?.videoId == videoId ||
            (optimisticVersionSong.value != null && videoId != convertedAudioId.value && videoId != convertedVideoId.value && videoId != keepVideoId.value)
        ) {
            optimisticVersionSong.value = null
        }
        if (activeRadioSeed.value?.first != videoId) activeRadioSeed.value = null
        if (keepVideoId.value != videoId) keepVideoId.value = null
        if (videoId != convertedAudioId.value) {
            convertedFromVideo.value = null
            convertedAudioId.value = null
            switchingAudioVersion.value = false
        }
        if (videoId != convertedVideoId.value) {
            convertedFromAudio.value = null
            convertedVideoId.value = null
            switchingAudioVersion.value = false
        }
    }

    suspend fun switchToMusicOnly(song: Song, pauseWhileResolving: Boolean) {
        val controller = mediaController ?: return
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount ||
            controller.currentMediaItem?.mediaId != song.videoId ||
            switchingAudioVersion.value
        ) return

        val resumeAfterResolution = pauseWhileResolving && controller.playWhenReady
        switchingAudioVersion.value = true
        keepVideoId.value = null
        val holdUntilAligned = AppSettings.smartVersionAlignment.value
        if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = true
        if (pauseWhileResolving) controller.pause()
        try {
            TrackLog.d("Player", "audio switch requested for '${song.title}'", song.videoId)
            val audio = runCatching { YtMusicRepository.resolveAudio(song) }.getOrNull()
            val stillCurrent = controller.currentMediaItemIndex == index &&
                controller.currentMediaItem?.mediaId == song.videoId

            if (audio == null || audio.videoId == song.videoId) {
                TrackLog.w("Player", "audio switch found no distinct official song", song.videoId)
                if (song.videoId !in missingVersions) missingVersions += song.videoId
                if (stillCurrent) {
                    showQueueNotice(versionContext.getString(R.string.no_audio_version))
                }
                if (stillCurrent && resumeAfterResolution) controller.play()
                return
            }
            if (!stillCurrent) {
                TrackLog.d("Player", "audio switch discarded; listener changed track", song.videoId)
                return
            }

            convertedFromVideo.value = song
            convertedAudioId.value = audio.videoId
            TrackLog.d("Player", "audio switch applying '${audio.title}' (${audio.videoId})", song.videoId)
            val target = audio.copy(
                isVideoOrigin = true,
                queueTier = song.queueTier,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType,
                playbackSourceId = song.playbackSourceId,
            )
            if (!holdUntilAligned ||
                VersionAudioAligner.getCachedOffsetMs(song.videoId, target.videoId) != null ||
                YtMusicRepository.versionTimeMap(song.videoId, target.videoId) != null
            ) {
                optimisticVersionSong.value = target
            }
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            controller.swapToVersion(target)
        } finally {
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            switchingAudioVersion.value = false
        }
    }

    suspend fun switchToVideo(song: Song, pauseWhileResolving: Boolean) {
        val controller = mediaController ?: return
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount ||
            controller.currentMediaItem?.mediaId != song.videoId ||
            switchingAudioVersion.value
        ) return

        val resumeAfterResolution = pauseWhileResolving && controller.playWhenReady
        switchingAudioVersion.value = true
        val holdUntilAligned = AppSettings.smartVersionAlignment.value
        if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = true
        if (pauseWhileResolving) controller.pause()
        try {
            TrackLog.d("Player", "video switch requested for '${song.title}'", song.videoId)
            val video = runCatching { YtMusicRepository.resolveVideo(song) }.getOrNull()
            val stillCurrent = controller.currentMediaItemIndex == index &&
                controller.currentMediaItem?.mediaId == song.videoId

            if (video == null || video.videoId == song.videoId) {
                TrackLog.w("Player", "video switch found no distinct video", song.videoId)
                if (song.videoId !in missingVersions) missingVersions += song.videoId
                if (stillCurrent) {
                    showQueueNotice(versionContext.getString(R.string.no_video_version))
                }
                if (stillCurrent && resumeAfterResolution) controller.play()
                return
            }
            if (!stillCurrent) {
                TrackLog.d("Player", "video switch discarded; listener changed track", song.videoId)
                return
            }

            convertedFromAudio.value = song
            convertedVideoId.value = video.videoId
            TrackLog.d("Player", "video switch applying '${video.title}' (${video.videoId})", song.videoId)
            val target = video.copy(
                queueTier = song.queueTier,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType,
                playbackSourceId = song.playbackSourceId,
            )
            if (!holdUntilAligned ||
                VersionAudioAligner.getCachedOffsetMs(song.videoId, target.videoId) != null ||
                YtMusicRepository.versionTimeMap(song.videoId, target.videoId) != null
            ) {
                optimisticVersionSong.value = target
            }
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            controller.swapToVersion(target)
        } finally {
            if (holdUntilAligned) AppSettings.versionAlignmentInProgress.value = false
            switchingAudioVersion.value = false
        }
    }
}
