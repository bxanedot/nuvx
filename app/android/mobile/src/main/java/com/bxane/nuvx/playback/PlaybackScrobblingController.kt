package com.bxane.nuvx.playback

import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.scrobbling.LastFM
import com.bxane.nuvx.data.scrobbling.ScrobbleManager
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class PlaybackScrobblingController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    private var scrobbleManager: ScrobbleManager? = null

    fun observe() {
        scope.launch {
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmPrimaryArtistOnly,
                AppSettings.lastfmSessionKey,
                AppSettings.lastfmApiKey,
                AppSettings.lastfmSecret,
                AppSettings.lastfmEndpoint,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    primaryArtistOnly = values[3] as Boolean,
                    sessionKey = values[4] as String,
                    apiKey = values[5] as String,
                    secret = values[6] as String,
                    endpoint = values[7] as String,
                    minDuration = values[8] as Int,
                    delayPercent = values[9] as Float,
                    delaySeconds = values[10] as Int,
                )
            }.collectLatest { snapshot ->
                val shouldEnable = AppSettings.scrobblingAvailable &&
                    snapshot.lastfmEnabled &&
                    snapshot.scrobbleEnabled &&
                    snapshot.sessionKey.isNotBlank() &&
                    snapshot.apiKey.isNotBlank() &&
                    snapshot.secret.isNotBlank()

                if (!shouldEnable) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                    return@collectLatest
                }

                LastFM.configure(
                    endpoint = snapshot.endpoint.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
                    apiKey = snapshot.apiKey,
                    secret = snapshot.secret,
                    sessionKey = snapshot.sessionKey,
                )
                val manager = scrobbleManager ?: ScrobbleManager(scope).also {
                    scrobbleManager = it
                }
                manager.minSongDuration = snapshot.minDuration
                manager.scrobbleDelayPercent = snapshot.delayPercent
                manager.scrobbleDelaySeconds = snapshot.delaySeconds
                manager.useNowPlaying = snapshot.nowPlaying
                manager.usePrimaryArtistOnly = snapshot.primaryArtistOnly

                player()?.let { exoPlayer ->
                    if (exoPlayer.isPlaying) {
                        manager.onPlayerStateChanged(
                            isPlaying = true,
                            song = exoPlayer.currentMediaItem?.toSong(),
                            durationMs = exoPlayer.duration.takeIf { it > 0 },
                        )
                    }
                }
            }
        }
    }

    fun onPlayerStateChanged(isPlaying: Boolean, song: Song?, durationMs: Long?) {
        scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)
    }

    fun onSongStop() {
        scrobbleManager?.onSongStop()
    }

    fun onSongStart(song: Song?, durationMs: Long?) {
        scrobbleManager?.onSongStart(song, durationMs)
    }

    fun release() {
        scrobbleManager?.destroy()
        scrobbleManager = null
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val primaryArtistOnly: Boolean,
        val sessionKey: String,
        val apiKey: String,
        val secret: String,
        val endpoint: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )
}
