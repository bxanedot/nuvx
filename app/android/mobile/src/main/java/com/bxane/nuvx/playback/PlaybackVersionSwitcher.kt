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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackVersionSwitcher(
    private val mediaController: MediaController?,
    private val versionContext: Context,
    private val scope: CoroutineScope,
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

    fun versionEffectiveSong(song: Song): Song = optimisticVersionSong.value?.takeIf {
        it.videoId == convertedAudioId.value || it.videoId == convertedVideoId.value || it.videoId == keepVideoId.value ||
            it.videoId == YtMusicRepository.cachedAudioVersion(song.videoId)?.videoId ||
            it.videoId == YtMusicRepository.cachedVideoVersion(song.videoId)?.videoId
    } ?: song

    fun isAudioVersion(song: Song): Boolean = if (optimisticVersionSong.value != null) {
        !optimisticVersionSong.value!!.isVideo
    } else {
        !song.isVideo && convertedVideoId.value != song.videoId
    }

    fun toggleVersion(song: Song, effectiveSong: Song, inParty: Boolean) {
        if (inParty) {
            showQueueNotice(versionContext.getString(R.string.version_switch_in_party))
            return
        }
        if (switchingAudioVersion.value) return
        val controller = mediaController
        val original = convertedFromVideo.value
        val originalAudio = convertedFromAudio.value
        when {
            controller == null -> Unit
            original != null && (
                convertedAudioId.value == song.videoId ||
                    convertedAudioId.value == effectiveSong.videoId ||
                    convertedAudioId.value == optimisticVersionSong.value?.videoId
                ) -> {
                keepVideoId.value = original.videoId
                convertedFromVideo.value = null
                convertedAudioId.value = null
                if (!versionAlignmentPending(song.videoId, original.videoId)) optimisticVersionSong.value = original
                controller.swapToVersion(original)
            }
            originalAudio != null && (
                convertedVideoId.value == song.videoId ||
                    convertedVideoId.value == effectiveSong.videoId ||
                    convertedVideoId.value == optimisticVersionSong.value?.videoId
                ) -> {
                convertedFromAudio.value = null
                convertedVideoId.value = null
                if (!versionAlignmentPending(song.videoId, originalAudio.videoId)) optimisticVersionSong.value = originalAudio
                controller.swapToVersion(originalAudio)
            }
            effectiveSong.isVideo || song.isVideo -> {
                val cached = YtMusicRepository.cachedAudioVersion(song.videoId)
                    ?: YtMusicRepository.cachedAudioVersion(effectiveSong.videoId)
                if (cached != null && cached.videoId != song.videoId && !versionAlignmentPending(song.videoId, cached.videoId)) {
                    optimisticVersionSong.value = cached.copy(
                        isVideoOrigin = true,
                        queueTier = song.queueTier,
                        queueEntryId = song.queueEntryId,
                        radioName = song.radioName,
                        playbackSource = song.playbackSource,
                        playbackSourceType = song.playbackSourceType,
                        playbackSourceId = song.playbackSourceId,
                    )
                }
                scope.launch { switchToMusicOnly(song, pauseWhileResolving = false) }
            }
            else -> {
                val cached = YtMusicRepository.cachedVideoVersion(song.videoId)
                    ?: YtMusicRepository.cachedVideoVersion(effectiveSong.videoId)
                if (cached != null && cached.videoId != song.videoId && !versionAlignmentPending(song.videoId, cached.videoId)) {
                    optimisticVersionSong.value = cached.copy(
                        queueTier = song.queueTier,
                        queueEntryId = song.queueEntryId,
                        radioName = song.radioName,
                        playbackSource = song.playbackSource,
                        playbackSourceType = song.playbackSourceType,
                        playbackSourceId = song.playbackSourceId,
                    )
                }
                scope.launch { switchToVideo(song, pauseWhileResolving = false) }
            }
        }
    }

    private fun versionAlignmentPending(sourceId: String, targetId: String): Boolean =
        AppSettings.smartVersionAlignment.value &&
            VersionAudioAligner.getCachedOffsetMs(sourceId, targetId) == null &&
            YtMusicRepository.versionTimeMap(sourceId, targetId) == null

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
