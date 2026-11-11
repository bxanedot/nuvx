package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.lyrics.EmbeddedLyrics
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsRepository
import com.bxane.nuvx.data.model.isLiveRadio
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
internal class PlaybackLyricsController(
    private val context: () -> Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val crossfade: () -> CrossfadeController?,
    private val loopMixLyricsTimeoutMs: Long,
) {

    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    private var lastPublishedSubtitle: String? = null
    private var lyricsTickerJob: Job? = null

    val subtitle: String? get() = lastPublishedSubtitle
    suspend fun loopMixLyrics(item: MediaItem, durationMs: Long): List<LyricLine>? {
        if (!AppSettings.syncedLyrics.value) return null
        val song = item.toSong()
        if (isLiveRadio(song.videoId)) return null
        return kotlinx.coroutines.withTimeoutOrNull(loopMixLyricsTimeoutMs) {
            song.localUri?.let { EmbeddedLyrics.forUri(context(), it) }
                ?: LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                )?.lines
        }
    }

    fun loadLyricsForCurrentTrack() {
        val currentSong = player()?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value || isLiveRadio(currentSong.videoId)) {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        val trackDurationMs = (player()?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(context(), localUri)
            }
            if (lines == null) {
                val found = LyricsRepository.lyrics(
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    durationMs = trackDurationMs,
                    album = currentSong.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                lines = found?.lines
            }
            withContext(Dispatchers.Main) {
                serviceLyrics = lines
                if (player()?.isPlaying == true) {
                    updateLyricSubtitle()
                }
            }
        }
    }

    fun startLyricsTicker() {
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateLyricSubtitle()
                delay(500L)
            }
        }
    }

    fun stopLyricsTicker() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        updateLyricSubtitle()
    }

    fun updateLyricSubtitle() {
        val exoPlayer = player() ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = crossfade()?.loopPositionMs() ?: exoPlayer.currentPosition
        val subtitleText = if (lines != null && lines.isNotEmpty() && AppSettings.syncedLyrics.value) {
            val idx = lines.indexOfLast { it.timeMs <= pos }
            val currentLine = lines.getOrNull(idx)
            if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank()) {
                "♪ ${currentLine.text}"
            } else {
                currentSong.artist
            }
        } else {
            currentSong.artist
        }

        if (subtitleText != lastPublishedSubtitle) {
            lastPublishedSubtitle = subtitleText
            exoPlayer.playlistMetadata = exoPlayer.mediaMetadata
                .buildUpon()
                .setSubtitle(subtitleText)
                .build()
        }
    }

    fun release() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
    }
}
