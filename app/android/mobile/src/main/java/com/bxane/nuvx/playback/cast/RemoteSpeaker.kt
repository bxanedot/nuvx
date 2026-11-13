package com.bxane.nuvx.playback.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

interface CastSink {
    fun onRemotePlaybackChanged()

    fun onRemoteRouteChanged()

    fun onRemoteVolumeChanged()
}

interface RemoteSpeaker {
    var sink: CastSink?

    val active: Boolean
    val playWhenReady: Boolean
    val isPlaying: Boolean

    val playbackState: Int
    val positionMs: Long

    val durationMs: Long

    val deviceVolume: Int

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    fun setVolume(fraction: Float)

    fun onLocalTransition(item: MediaItem?, reason: Int)

    fun onLocalQueueChanged()
    fun onLocalRepeatChanged(repeatMode: Int)

    fun onLocalStartedPlaying(player: Player)
}

class Speakers(private val all: List<RemoteSpeaker>) : RemoteSpeaker {

    private val current: RemoteSpeaker get() = all.firstOrNull { it.active } ?: all.first()

    override var sink: CastSink? = null
        set(value) {
            field = value
            all.forEach { it.sink = value }
        }

    override val active: Boolean get() = all.any { it.active }
    override val playWhenReady: Boolean get() = current.playWhenReady
    override val isPlaying: Boolean get() = current.isPlaying
    override val playbackState: Int get() = current.playbackState
    override val positionMs: Long get() = current.positionMs
    override val durationMs: Long get() = current.durationMs
    override val deviceVolume: Int get() = current.deviceVolume

    override fun play() = current.play()
    override fun pause() = current.pause()
    override fun seekTo(positionMs: Long) = current.seekTo(positionMs)
    override fun setVolume(fraction: Float) = current.setVolume(fraction)

    override fun onLocalTransition(item: MediaItem?, reason: Int) = all.forEach { it.onLocalTransition(item, reason) }
    override fun onLocalQueueChanged() = all.forEach { it.onLocalQueueChanged() }
    override fun onLocalRepeatChanged(repeatMode: Int) = all.forEach { it.onLocalRepeatChanged(repeatMode) }
    override fun onLocalStartedPlaying(player: Player) = all.forEach { it.onLocalStartedPlaying(player) }
}

object NoSpeaker : RemoteSpeaker {
    override var sink: CastSink? = null
    override val active = false
    override val playWhenReady = false
    override val isPlaying = false
    override val playbackState = Player.STATE_IDLE
    override val positionMs = 0L
    override val durationMs = C.TIME_UNSET
    override val deviceVolume = 0
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setVolume(fraction: Float) = Unit
    override fun onLocalTransition(item: MediaItem?, reason: Int) = Unit
    override fun onLocalQueueChanged() = Unit
    override fun onLocalRepeatChanged(repeatMode: Int) = Unit
    override fun onLocalStartedPlaying(player: Player) = Unit
}
