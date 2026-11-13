package com.bxane.nuvx.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.sources.TrackMatcher

internal class PlaybackQueuePrefetchController(
    private val driveDirectWindow: () -> Unit,
) {
    private var preferredPrefetchRequest: List<String>? = null

    fun prefetchAround(player: ExoPlayer) {
        if (DirectSourceGate.isActive()) {
            driveDirectWindow()
            if (preferredPrefetchRequest != null) cancelPrefetch()
            return
        }
        val nextIndex = player.nextMediaItemIndex
        val upcomingSongs = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map(player::getMediaItemAt)
                .filterNot(MusicOnlyPlayback::wants)
                .map { it.toSong() }
        } else {
            emptyList()
        }
        AudioCache.noteSongs(upcomingSongs)
        val request = upcomingSongs.map { it.videoId }
        if (request == preferredPrefetchRequest) return
        preferredPrefetchRequest = request

        fun warm(songs: List<Song>) {
            AudioCache.prefetchQueue(
                songs.map { song ->
                    AudioCache.Upcoming(
                        mediaId = song.videoId,
                        target = TrackMatcher.targetOf(song),
                    )
                },
            )
        }

        warm(upcomingSongs)
    }

    fun cancelPrefetch() {
        preferredPrefetchRequest = null
        AudioCache.cancel()
    }
}
