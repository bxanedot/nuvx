package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.discord.DiscordRPC
import com.bxane.nuvx.data.discord.discordAudioQualityLine
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackDiscordPresenceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val teardownTimeoutMs: Long,
) {
    private var discordRpc: DiscordRPC? = null
    private var discordUpdateJob: Job? = null
    private var discordPresenceUp = false

    fun observe() {
        scope.launch {
            combine(
                AppSettings.discordToken,
                AppSettings.discordRpcEnabled,
            ) { token, enabled -> token.takeIf { enabled && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    discordUpdateJob?.cancel()
                    discordRpc?.let { rpc ->
                        val wasUp = discordPresenceUp
                        discordPresenceUp = false
                        withContext(Dispatchers.IO + NonCancellable) {
                            withTimeoutOrNull(teardownTimeoutMs) {
                                if (wasUp) runCatching { rpc.close() }
                            }
                            runCatching { rpc.closeRPC() }
                        }
                    }
                    discordRpc = null

                    if (token == null) return@collectLatest
                    discordRpc = DiscordRPC(context, token)
                    player()?.takeIf { it.isPlaying }?.let(::push)
                }
        }

        scope.launch {
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
                AppSettings.playbackSpeed,
            ) { it.toList() }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    player()?.takeIf { activePlayer -> activePlayer.isPlaying }?.let(::push)
                }
        }

        scope.launch {
            combine(
                NerdStats.current,
                AppSettings.discordShowAudioQuality,
            ) { stats, enabled ->
                discordAudioQualityLine(stats).takeIf { enabled }
            }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    player()?.takeIf { activePlayer -> activePlayer.isPlaying }?.let(::push)
                }
        }

        scope.launch {
            AppSettings.meteredConnection
                .drop(1)
                .collect { metered ->
                    if (metered == null) return@collect
                    val rpc = discordRpc ?: return@collect
                    withContext(Dispatchers.IO) { runCatching { rpc.wakeUp() } }
                    if (discordPresenceUp) {
                        player()?.takeIf { activePlayer -> activePlayer.isPlaying }?.let(::push)
                    }
                }
        }
    }

    fun push(exoPlayer: ExoPlayer) {
        val rpc = discordRpc ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val speed = AppSettings.playbackSpeed.value
        val audioQuality = discordAudioQualityLine(NerdStats.current.value)
            .takeIf { AppSettings.discordShowAudioQuality.value }

        discordUpdateJob?.cancel()
        discordPresenceUp = true
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            rpc.updateSong(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = AppSettings.discordUseDetails.value,
                status = AppSettings.discordStatus.value,
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                activityType = AppSettings.discordActivityType.value,
                activityName = AppSettings.discordActivityName.value,
                audioQuality = audioQuality,
            ).onFailure {
                TrackLog.d("nuvx", "Discord presence failed: ${it.message}", about = song.videoId)
            }
        }
    }

    fun clear() {
        val rpc = discordRpc ?: return
        if (!discordPresenceUp) return
        discordPresenceUp = false
        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            runCatching { rpc.close() }
        }
    }

    fun release() {
        discordRpc?.let { rpc ->
            discordRpc = null
            val wasUp = discordPresenceUp
            discordPresenceUp = false
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(teardownTimeoutMs) {
                    if (wasUp) runCatching { rpc.close() }
                }
                runCatching { rpc.closeRPC() }
            }
        }
    }
}
