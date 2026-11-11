package com.bxane.nuvx.ui

import android.content.Context
import android.widget.Toast
import androidx.media3.session.MediaController
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import com.bxane.nuvx.R
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.partyQueueIndexOf
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isUnresolvedSpotify
import com.bxane.nuvx.playback.BoundedQueuePlanner
import com.bxane.nuvx.playback.QueueCoordinator
import com.bxane.nuvx.playback.QueueSource
import com.bxane.nuvx.playback.cast.CastController
import com.bxane.nuvx.playback.playBoundedContext
import com.bxane.nuvx.playback.playSongs
import com.bxane.nuvx.playback.beginRadioQueue
import com.bxane.nuvx.playback.commitRadioQueue
import com.bxane.nuvx.playback.fromAutoplay
import com.bxane.nuvx.playback.loadAutoplayTracks
import com.bxane.nuvx.playback.toMediaItem
import com.bxane.nuvx.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainPlaybackStarter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: () -> MediaController?,
    private val currentQueue: () -> List<Song>,
    private val refusedByHost: () -> Boolean,
    private val playRequestGeneration: MutableIntState,
    private val activeRadioSeed: MutableState<Pair<String, String>?>,
) {
    fun playFrom(
        allSongs: List<Song>,
        allIndex: Int,
        source: QueueSource,
        boundedContext: Boolean = false,
        continuationToken: String? = null,
    ) {
        if (allSongs.getOrNull(allIndex)?.isUnresolvedSpotify == true) return
        val songs = allSongs.filterNot { it.isUnresolvedSpotify }
        val index = songs.indexOf(allSongs.getOrNull(allIndex)).coerceAtLeast(0)
        playRequestGeneration.intValue++
        activeRadioSeed.value = null
        scope.launch {
            if (refusedByHost()) return@launch
            val c = controller() ?: return@launch
            val currentTimeline = currentQueue().takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex

            if (ListenTogether.state.value.inParty) {
                val selectedSong = songs.getOrNull(index) ?: return@launch
                val party = ListenTogether.state.value
                val partyQueue = party.queue.items
                val partyIndex = partyQueueIndexOf(party.queue, party.playback, party.playback.track?.videoId)
                val upcomingPartyTracks = if (partyIndex >= 0) {
                    partyQueue.drop(partyIndex + 1)
                } else {
                    emptyList()
                }
                val timeline = QueueCoordinator.buildPartyPlaybackQueue(
                    tappedSong = selectedSong,
                    source = source,
                    upcomingPartyTracks = upcomingPartyTracks,
                )
                c.playSongs(timeline, 0)
            } else {
                val bounded = boundedContext || songs.size > BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT
                if (bounded && !CastController.state.value.casting) {
                    c.playBoundedContext(songs, index, source, continuationToken)
                } else {
                    val result = QueueCoordinator.buildContextQueue(
                        currentTimeline = currentTimeline,
                        currentIndex = currentIndex,
                        newContextSongs = songs,
                        selectedIndex = index,
                        contextSource = source,
                    )
                    c.playSongs(result.timeline, result.startIndex)
                }
            }
        }
    }

    fun playRadio(song: Song, source: QueueSource) {
        playRequestGeneration.intValue++
        activeRadioSeed.value = null
        scope.launch {
            if (refusedByHost()) return@launch
            val c = controller() ?: return@launch
            val currentTimeline = currentQueue().takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex
            val oneOffQueue = QueueCoordinator.buildOneOffQueue(
                currentTimeline = currentTimeline,
                currentIndex = currentIndex,
                tappedSong = song,
                source = source,
            )
            c.playSongs(oneOffQueue, 0)
        }
    }

    fun startRadio(song: Song) {
        val originalController = controller()
        if (originalController != null && !refusedByHost()) {
            val request = ++playRequestGeneration.intValue
            val originalManualQueue = (0 until originalController.mediaItemCount)
                .map { originalController.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            scope.launch {
                val seed = song.copy(
                    radioName = song.title,
                    playbackSource = song.title,
                    playbackSourceType = PlaybackSourceType.SHARED_LINK,
                    playbackSourceId = song.videoId,
                )
                val related = loadAutoplayTracks(
                    existing = listOf(seed),
                    seedSong = seed,
                    limit = INITIAL_RADIO_TRACKS,
                ).getOrElse {
                    if (request == playRequestGeneration.intValue) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (related.isEmpty()) {
                    if (request == playRequestGeneration.intValue) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val activeController = originalController
                val activeManualQueue = (0 until activeController.mediaItemCount)
                    .map { activeController.getMediaItemAt(it) }
                    .filterNot { it.fromAutoplay }
                    .map { it.mediaId }
                if (request != playRequestGeneration.intValue || activeManualQueue != originalManualQueue) {
                    return@launch
                }
                activeController.beginRadioQueue()
                val currentIndex = activeController.currentMediaItemIndex
                val currentItem = activeController.currentMediaItem
                if (currentIndex >= 0 && currentItem?.mediaId == song.videoId) {
                    activeController.addMediaItems(currentIndex + 1, related.map { it.toMediaItem() })
                    val oldTail = currentIndex + 1 + related.size
                    if (oldTail < activeController.mediaItemCount) {
                        activeController.removeMediaItems(oldTail, activeController.mediaItemCount)
                    }
                    if (currentIndex > 0) activeController.removeMediaItems(0, currentIndex)
                    activeRadioSeed.value = song.videoId to song.title
                } else {
                    activeRadioSeed.value = null
                    activeController.playSongs(listOf(seed) + related, 0)
                }
                activeController.commitRadioQueue()
                Toast.makeText(
                    context,
                    context.getString(R.string.radio_started, song.title),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

private const val INITIAL_RADIO_TRACKS = 24
