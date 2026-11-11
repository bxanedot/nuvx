package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.session.MediaController
import com.bxane.nuvx.R
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackQueueActions(
    private val scope: CoroutineScope,
    private val context: Context,
    private val mediaController: () -> MediaController?,
    private val currentQueue: () -> List<Song>,
    private val queueLabel: String,
    private val refusedByHost: () -> Boolean,
    private val showQueueNotice: (String) -> Unit,
    private val play: (List<Song>, Int) -> Unit,
) {
    fun addToQueue(song: Song) {
        scope.launch {
            if (refusedByHost()) return@launch
            mediaController()?.let {
                if (ListenTogether.state.value.inParty) {
                    val upcoming = (it.mediaItemCount - (it.currentMediaItemIndex + 1)).coerceAtLeast(0)
                    if (upcoming >= 25) {
                        showQueueNotice(context.getString(R.string.party_queue_full, 25))
                        return@launch
                    }
                }
                val current = it.currentMediaItem?.toSong()
                val timeline = currentQueue().takeIf { queue -> queue.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { index -> it.getMediaItemAt(index).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = false,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_added_to_queue))
            }
        }
    }

    fun playNext(song: Song) {
        scope.launch {
            if (refusedByHost()) return@launch
            mediaController()?.let {
                if (ListenTogether.state.value.inParty) {
                    val upcoming = (it.mediaItemCount - (it.currentMediaItemIndex + 1)).coerceAtLeast(0)
                    if (upcoming >= 25) {
                        showQueueNotice(context.getString(R.string.party_queue_full, 25))
                        return@launch
                    }
                }
                val current = it.currentMediaItem?.toSong()
                val timeline = currentQueue().takeIf { queue -> queue.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { index -> it.getMediaItemAt(index).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = true,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_will_play_next))
            }
        }
    }

    fun onSongSwipe(song: Song) {
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    fun queueSongs(songs: List<Song>, next: Boolean) {
        if (songs.isNotEmpty()) {
            scope.launch {
                if (refusedByHost()) return@launch
                val controller = mediaController()
                if (controller == null || controller.mediaItemCount == 0) {
                    play(songs, 0)
                } else {
                    val toAdd = if (ListenTogether.state.value.inParty) {
                        val upcoming = (controller.mediaItemCount - (controller.currentMediaItemIndex + 1))
                            .coerceAtLeast(0)
                        val slotsLeft = (25 - upcoming).coerceAtLeast(0)
                        if (slotsLeft <= 0) {
                            showQueueNotice(context.getString(R.string.party_queue_full, 25))
                            return@launch
                        }
                        songs.take(slotsLeft)
                    } else {
                        songs
                    }
                    val timeline = currentQueue().takeIf { queue -> queue.size == controller.mediaItemCount }
                        ?: (0 until controller.mediaItemCount).map { index ->
                            controller.getMediaItemAt(index).toSong()
                        }
                    val at = QueueCoordinator.findUserQueueInsertionIndex(
                        timeline = timeline,
                        currentIndex = controller.currentMediaItemIndex,
                        isNext = next,
                    )
                    val current = controller.currentMediaItem?.toSong()
                    controller.addMediaItems(
                        at,
                        toAdd.map {
                            it.copy(
                                radioName = current?.radioName,
                                playbackSource = current?.playbackSource ?: queueLabel,
                                playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                                playbackSourceId = current?.playbackSourceId,
                            ).asQueueEntry(QueueTier.USER_QUEUE).toMediaItem()
                        },
                    )
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        toAdd.size,
                        toAdd.size,
                    )
                    showQueueNotice(message)
                }
            }
        }
    }
}
