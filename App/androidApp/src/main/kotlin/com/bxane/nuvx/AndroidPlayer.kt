package com.bxane.nuvx

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bxane.nuvx.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidPlayer(private val controller: Player) : NuvxPlayer, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlayerState())
    override val state = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    init {
        controller.addListener(listener)
        scope.launch { while (isActive) { publish(); delay(500) } }
    }

    private fun publish() {
        val tracks = (0 until controller.mediaItemCount).map { i ->
            val item = controller.getMediaItemAt(i)
            val meta = item.mediaMetadata
            val extras = meta.extras?.keySet()?.associateWith { meta.extras?.getString(it).orEmpty() }.orEmpty()
            trackFromMetadata(item.mediaId, meta.title?.toString().orEmpty(), meta.artist?.toString(), meta.albumTitle?.toString(), meta.artworkUri?.toString(), extras)
        }
        mutableState.value = PlayerState(tracks, if (tracks.isEmpty()) -1 else controller.currentMediaItemIndex,
            controller.isPlaying, controller.playbackState == Player.STATE_BUFFERING,
            controller.currentPosition.coerceAtLeast(0), controller.duration.coerceAtLeast(0),
            controller.playerError?.let { "Playback failed. Retry this track or choose another engine in Settings." },
            when (controller.repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.ONE; Player.REPEAT_MODE_ALL -> RepeatMode.ALL; else -> RepeatMode.OFF },
            controller.volume)
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder().setMediaId(videoId)
        .setUri(localUri ?: "nuvx://track/$videoId")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artistLine).setAlbumTitle(album)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .setExtras(Bundle().apply { toMetadataExtras().forEach { (key, value) -> putString(key, value) } })
            .build()).build()

    override fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        controller.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
        controller.prepare()
        controller.play()
    }
    override fun enqueue(tracks: List<Track>) { controller.addMediaItems(tracks.map { it.toMediaItem() }) }
    override fun playNext(tracks: List<Track>) {
        if (controller.mediaItemCount == 0) return playQueue(tracks, 0)
        controller.addMediaItems(controller.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
    }
    override fun move(from: Int, to: Int) { if (from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount) controller.moveMediaItem(from, to) }
    override fun setRepeat(mode: RepeatMode) {
        controller.repeatMode = when (mode) { RepeatMode.ONE -> Player.REPEAT_MODE_ONE; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.OFF -> Player.REPEAT_MODE_OFF }
    }
    override fun setVolume(volume: Float) { controller.volume = volume.coerceIn(0f, 1f) }
    override fun shuffleUpNext() {
        val from = controller.currentMediaItemIndex + 1
        val count = controller.mediaItemCount
        if (count - from < 2) return
        val upNext = (from until count).map { controller.getMediaItemAt(it) }.shuffled()
        controller.removeMediaItems(from, count)
        controller.addMediaItems(from, upNext)
    }
    override fun remove(index: Int) { if (index in 0 until controller.mediaItemCount) controller.removeMediaItem(index) }
    override fun togglePlay() {
        if (controller.playbackState == Player.STATE_ENDED) { controller.seekToDefaultPosition(); controller.play() }
        else if (controller.playWhenReady && controller.playerError == null) controller.pause()
        else { if (controller.playerError != null || controller.playbackState == Player.STATE_IDLE) controller.prepare(); controller.play() }
    }
    override fun seekTo(positionMs: Long) { controller.seekTo(positionMs.coerceAtLeast(0)) }
    override fun next() { controller.seekToNextMediaItem() }
    override fun previous() { controller.seekToPrevious() }
    override fun skipTo(index: Int) { if (index in 0 until controller.mediaItemCount) { controller.seekTo(index, 0); controller.prepare(); controller.play() } }
    override fun close() { scope.cancel(); controller.removeListener(listener) }
}
