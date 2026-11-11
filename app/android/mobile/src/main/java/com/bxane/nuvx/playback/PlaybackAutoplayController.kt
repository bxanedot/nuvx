package com.bxane.nuvx.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.partyQueueIndexOf
import com.bxane.nuvx.data.listentogether.partyUpcomingAfter
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isLiveRadio
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackAutoplayController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val boundedQueueActive: () -> Boolean,
    private val autoplayEligible: (ExoPlayer) -> Boolean,
) {
    private var autoplayLoadJob: Job? = null
    private var autoplaySeed: String? = null
    private var repeatAllStash: List<MediaItem> = emptyList()
    private var repeatAllStashSeed: String? = null
    private var autoplayAheadSeen = 0
    private val sessionSongHistory = mutableListOf<Song>()
    private val partyAutoplayLock = Mutex()

    fun resetAndLoadForCurrentTrack() {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    fun loadForCurrentTrack() {
        loadAutoplayForCurrentTrack()
    }

    fun disableAutoplay() {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        dropAutoplayTracksFromQueue()
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
    }

    fun clearRepeatAllStash() {
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
    }

    fun clearRadioQueueSession() {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        sessionSongHistory.clear()
    }

    fun stashAutoplayTracks() {
        val exoPlayer = player() ?: return
        if (repeatAllStash.isNotEmpty()) return
        val dropped = dropAutoplayTracksFromQueue()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = exoPlayer.currentMediaItem?.mediaId
    }

    fun restoreAutoplayTracks() {
        val exoPlayer = player() ?: return
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !autoplayEnabled(ListenTogether.state.value)) return
        if (exoPlayer.currentMediaItem?.mediaId != seed) return
        val present = (0 until exoPlayer.mediaItemCount)
            .mapTo(mutableSetOf()) { exoPlayer.getMediaItemAt(it).mediaId }
        val restored = stashed.filter { it.mediaId !in present }
        if (restored.isEmpty()) return
        exoPlayer.addMediaItems(restored)
    }

    fun refreshIfQueueEmpty() {
        val exoPlayer = player() ?: return
        val aheadNow = (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
            .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val tailShrank = aheadNow < autoplayAheadSeen
        autoplayAheadSeen = aheadNow
        if (!autoplayQueueNeedsRefresh(
                enabled = autoplayEnabled(ListenTogether.state.value),
                repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
                currentIndex = exoPlayer.currentMediaItemIndex,
                itemCount = exoPlayer.mediaItemCount,
                loadInProgress = autoplayLoadJob?.isActive == true,
            ) && !(tailShrank && aheadNow < MAX_QUEUED_AUTOPLAY &&
                autoplayEnabled(ListenTogether.state.value) &&
                exoPlayer.repeatMode != Player.REPEAT_MODE_ALL &&
                autoplayLoadJob?.isActive != true)
        ) {
            return
        }
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }

    private fun loadAutoplayForCurrentTrack() {
        val exoPlayer = player() ?: return
        val party = ListenTogether.state.value
        if (!autoplayEnabled(party) ||
            exoPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
            (party.inParty && autoplaySupplierId(party) != party.you?.memberId)
        ) {
            return
        }
        if (boundedQueueActive() && !autoplayEligible(exoPlayer)) {
            return
        }
        val current = exoPlayer.currentMediaItem?.toSong() ?: return
        if (isLiveRadio(current.videoId)) return
        if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += current
        val queuedAutoplay = partyAutoplayWaiting(party, current.videoId)
            ?: (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
                .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        val needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay
        if (needed <= 0) return
        if (autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId
        autoplayLoadJob = scope.launch {
            var remaining = needed
            var emptyRefreshesRemaining = MAX_AUTOPLAY_EMPTY_REFRESHES
            var seedBack = 0
            while (isActive) {
                val activePlayer = player() ?: return@launch
                val activeParty = ListenTogether.state.value
                if (!autoplayEnabled(activeParty) ||
                    activePlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    activePlayer.currentMediaItem?.mediaId != current.videoId ||
                    (activeParty.inParty && autoplaySupplierId(activeParty) != activeParty.you?.memberId)
                ) {
                    return@launch
                }
                val queueSongs = (0 until activePlayer.mediaItemCount)
                    .map { activePlayer.getMediaItemAt(it).toSong() } +
                    activeParty.queue.items.takeIf { activeParty.inParty }.orEmpty().map { it.toSong() }
                val existing = if (AppSettings.dontRepeatSuggestions.value) {
                    queueSongs + sessionSongHistory
                } else {
                    queueSongs
                }
                val seedSong = if (seedBack == 0) {
                    current
                } else {
                    val at = activePlayer.currentMediaItemIndex - seedBack
                    if (at >= 0) activePlayer.getMediaItemAt(at).toSong() else current
                }
                val result = loadAutoplayTracks(existing, seedSong, remaining)
                val resolved = result.getOrElse {
                    TrackLog.w(
                        "nuvx",
                        "notification autoplay failed: ${it.message}",
                        about = current.videoId,
                    )
                    emptyList()
                }
                val latestPlayer = player() ?: return@launch
                val latestParty = ListenTogether.state.value
                if (!autoplayEnabled(latestParty) ||
                    latestPlayer.repeatMode == Player.REPEAT_MODE_ALL ||
                    latestPlayer.currentMediaItem?.mediaId != current.videoId ||
                    (latestParty.inParty && autoplaySupplierId(latestParty) != latestParty.you?.memberId)
                ) {
                    return@launch
                }
                if (resolved.isNotEmpty()) {
                    if (latestParty.inParty) {
                        val landed = topUpPartyAutoplay(current.videoId, resolved)
                        if (!landed) {
                            TrackLog.w(
                                "nuvx",
                                "party did not take AutoPlay's tracks; releasing the seed to retry",
                                about = current.videoId,
                            )
                            autoplaySeed = null
                            return@launch
                        }
                    } else {
                        val queuedNow = (0 until latestPlayer.mediaItemCount)
                            .map { latestPlayer.getMediaItemAt(it).toSong() }
                        val room = MAX_QUEUED_AUTOPLAY -
                            (latestPlayer.currentMediaItemIndex + 1 until latestPlayer.mediaItemCount)
                                .count { latestPlayer.getMediaItemAt(it).fromAutoplay }
                        val fresh = resolved
                            .filterNot { song -> queuedNow.any { QueueBuilder.isSameRecording(it, song) } }
                            .take(room.coerceAtLeast(0))
                        if (fresh.isNotEmpty()) latestPlayer.addMediaItems(fresh.map { it.toMediaItem() })
                    }
                    if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += resolved
                    return@launch
                }

                val at = latestPlayer.currentMediaItemIndex
                if (at < 0 || at != latestPlayer.mediaItemCount - 1) return@launch
                if (emptyRefreshesRemaining-- > 0) {
                    delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
                } else {
                    delay(AUTOPLAY_WATCHDOG_MS)
                    while (isActive && player()?.playWhenReady == false) delay(AUTOPLAY_WATCHDOG_MS)
                    seedBack = if (at - (seedBack + 1) >= 0) seedBack + 1 else 0
                }
                remaining = MAX_QUEUED_AUTOPLAY
            }
        }
    }

    private fun partyAutoplayWaiting(party: ListenTogether.State, videoId: String): Int? {
        if (!party.inParty) return null
        if (partyQueueIndexOf(party.queue, party.playback, videoId) < 0) return null
        return partyUpcomingAfter(party.queue, party.playback, videoId).count { it.fromAutoplay }
    }

    private suspend fun topUpPartyAutoplay(currentId: String, suggestions: List<Song>): Boolean =
        partyAutoplayLock.withLock {
            withContext(NonCancellable) {
                val party = ListenTogether.state.value
                val waiting = partyUpcomingAfter(party.queue, party.playback, currentId)
                val waitingIds = waiting.mapTo(HashSet()) { it.videoId }
                val room = MAX_QUEUED_AUTOPLAY - waiting.count { it.fromAutoplay }
                val fresh = suggestions.filterNot { it.videoId in waitingIds }.take(room.coerceAtLeast(0))
                if (fresh.isEmpty()) return@withContext true
                ListenTogether.queueAdd(fresh.map { it.toPartyTrack(0L) })
                val added = fresh.first().videoId
                withTimeoutOrNull(PARTY_QUEUE_ECHO_TIMEOUT_MS) {
                    ListenTogether.state.first { state -> state.queue.items.any { it.videoId == added } }
                } != null
            }
        }

    private fun autoplayEnabled(party: ListenTogether.State): Boolean =
        autoplayEnabledFor(party, AppSettings.autoplay.value)

    private fun dropAutoplayTracksFromQueue(): List<MediaItem> {
        val exoPlayer = player() ?: return emptyList()
        val dropped = mutableListOf<MediaItem>()
        for (index in exoPlayer.mediaItemCount - 1 downTo exoPlayer.currentMediaItemIndex + 1) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!item.fromAutoplay) continue
            dropped += item
            exoPlayer.removeMediaItem(index)
        }
        return dropped.reversed()
    }

    private companion object {
        const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L
        const val MAX_AUTOPLAY_EMPTY_REFRESHES = 1
        const val AUTOPLAY_WATCHDOG_MS = 15_000L
        const val PARTY_QUEUE_ECHO_TIMEOUT_MS = 5_000L
    }
}
