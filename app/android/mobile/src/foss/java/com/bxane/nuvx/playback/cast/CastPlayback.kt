package com.bxane.nuvx.playback.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope

class CastStream(val url: String, val mimeType: String)

class CastPlayback(
    scope: CoroutineScope,
    localPlayer: () -> ExoPlayer?,
    resolve: suspend (MediaItem) -> CastStream?,
    say: (Int) -> Unit,
) : RemoteSpeaker {
    override var sink: CastSink? = null
    override val active = false
    override val playWhenReady = false
    override val isPlaying = false
    override val playbackState = Player.STATE_IDLE
    override val positionMs = 0L
    override val durationMs = C.TIME_UNSET
    override val deviceVolume = 0
    var resumeHereOnEnd = false

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setVolume(fraction: Float) = Unit
    override fun onLocalTransition(item: MediaItem?, reason: Int) = Unit
    override fun onLocalQueueChanged() = Unit
    override fun onLocalRepeatChanged(repeatMode: Int) = Unit
    override fun onLocalStartedPlaying(player: Player) = Unit
    fun release() = Unit

    companion object {
        fun mimeTypeOf(uri: Uri): String {
            uri.getQueryParameter("mime")?.takeIf { it.isNotBlank() }?.let { return it }
            val path = uri.lastPathSegment?.lowercase().orEmpty()
            return when {
                path.endsWith(".flac") -> "audio/flac"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".m3u8") -> "application/x-mpegurl"
                else -> "audio/mp4"
            }
        }
    }
}
