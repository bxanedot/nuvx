package com.bxane.nuvx.ui

import androidx.media3.session.MediaController
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.partyQueueIndexOf
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isUnresolvedSpotify
import com.bxane.nuvx.playback.BoundedQueuePlanner
import com.bxane.nuvx.playback.QueueCoordinator
import com.bxane.nuvx.playback.QueueSource
import com.bxane.nuvx.playback.cast.CastController
import com.bxane.nuvx.playback.playBoundedContext
import com.bxane.nuvx.playback.playSongs
import com.bxane.nuvx.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainPlaybackStarter(
    private val scope: CoroutineScope,
    private val controller: () -> MediaController?,
    private val currentQueue: () -> List<Song>,
    private val refusedByHost: () -> Boolean,
    private val onRequestStart: () -> Unit,
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
        onRequestStart()
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
        onRequestStart()
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
}
