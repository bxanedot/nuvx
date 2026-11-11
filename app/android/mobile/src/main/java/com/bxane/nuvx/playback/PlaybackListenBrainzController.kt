package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.scrobbling.ListenBrainzManager
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class PlaybackListenBrainzController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    private var song: Song? = null
    private var startMs = 0L
    private var durationMs: Long? = null

    fun onIsPlayingChanged(song: Song, positionMs: Long, durationMs: Long?) {
        if (this.song?.videoId != song.videoId || startMs == 0L) {
            this.song = song
            startMs = System.currentTimeMillis()
            this.durationMs = durationMs
        } else if (this.durationMs == null) {
            this.durationMs = durationMs
        }
        submitPlayingNow(song, positionMs, durationMs)
    }

    fun onTrackChanged(newSong: Song?, isPlaying: Boolean, durationMs: Long?, previousEnded: Boolean) {
        val previousSong = song
        val previousStartMs = startMs
        if (previousSong != null && previousEnded && previousStartMs > 0L) {
            submitFinished(previousSong, previousStartMs, this.durationMs)
        }
        song = newSong
        startMs = if (isPlaying) System.currentTimeMillis() else 0L
        this.durationMs = durationMs
        if (newSong != null && isPlaying) {
            submitPlayingNow(newSong, 0L, durationMs)
        }
    }

    fun onPlaybackEnded(exoPlayer: ExoPlayer) {
        val lastSong = song
        if (lastSong != null && startMs > 0L) {
            submitFinished(lastSong, startMs, durationMs ?: exoPlayer.duration.takeIf { it > 0 })
        }
        song = null
        startMs = 0L
        durationMs = null
    }

    fun release() {
        val lastSong = song
        if (lastSong != null && startMs > 0L) {
            val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
            val lbToken = AppSettings.listenBrainzToken.value
            if (lbEnabled && lbToken.isNotBlank()) {
                val lastStart = startMs
                val lastDuration = player()?.duration?.takeIf { it > 0 }
                CoroutineScope(Dispatchers.IO).launch {
                    ListenBrainzManager.submitFinished(
                        lbToken,
                        lastSong,
                        lastStart,
                        System.currentTimeMillis(),
                        lastDuration,
                        AppSettings.listenBrainzPrimaryArtistOnly.value,
                    )
                }
            }
        }
    }

    private fun submitFinished(song: Song, startMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        val endMs = System.currentTimeMillis()
        scope.launch {
            ListenBrainzManager.submitFinished(
                lbToken,
                song,
                startMs,
                endMs,
                durationMs,
                AppSettings.listenBrainzPrimaryArtistOnly.value,
            )
        }
    }

    private fun submitPlayingNow(song: Song, positionMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        scope.launch {
            ListenBrainzManager.submitPlayingNow(
                lbToken,
                song,
                positionMs,
                durationMs,
                AppSettings.listenBrainzPrimaryArtistOnly.value,
            )
        }
    }
}
