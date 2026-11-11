package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.wear.WearBridge
import com.bxane.nuvx.widget.MediaWidget
import com.bxane.nuvx.widget.MediaWidgetSnapshot

internal class PlaybackWidgetPublisher(
    private val context: Context,
    private val player: () -> ExoPlayer?,
    private val reportedPlayer: () -> Player?,
    private val canLike: (MediaItem?) -> Boolean,
) {
    fun publish(playing: Boolean? = null) {
        val exoPlayer = player() ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val liked = (if (song.localUri != null) OfflinePlaylists.isLiked(song) else null)
            ?: LikeState.overrides.value[song.videoId]?.let { it == LikeStatus.LIKE }
            ?: MediaWidgetSnapshot.load(context).takeIf { it.mediaId == song.videoId }?.isLiked
            ?: false
        val reported: Player = reportedPlayer() ?: exoPlayer
        val snapshot = MediaWidgetSnapshot(
            mediaId = song.videoId,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            isPlaying = playing ?: reported.playWhenReady,
            hasPrevious = exoPlayer.hasPreviousMediaItem(),
            hasNext = exoPlayer.hasNextMediaItem(),
            isLiked = liked,
            canLike = canLike(exoPlayer.currentMediaItem),
            shuffleEnabled = QueueShuffle.enabled.value,
        )
        MediaWidgetSnapshot.save(context, snapshot)
        MediaWidget.refresh(context)
        WearBridge.publish(context, snapshot, reported, playing)
    }

    fun clear() {
        MediaWidgetSnapshot.save(context, MediaWidgetSnapshot.EMPTY)
        MediaWidget.refresh(context)
    }
}
