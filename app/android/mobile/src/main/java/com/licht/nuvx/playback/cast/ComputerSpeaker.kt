package com.licht.nuvx.playback.cast

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.licht.nuvx.data.sync.NuvxConnect
import com.licht.nuvx.data.sync.NuvxSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Nuvx Connect as a speaker: the paired computer plays the phone's queue while the phone's own player, mini player,
 * notification and lock screen control it and show its position. Same contract as Cast and the watch: the queue
 * stays on the phone; the computer gets the current song and the next ones.
 */
internal class ComputerSpeaker(
    private val scope: CoroutineScope,
    private val localPlayer: () -> ExoPlayer?,
    private val onChanged: () -> Unit,
) : RemoteSpeaker {
    data class State(val available: Boolean = false, val name: String? = null, val active: Boolean = false)

    override var sink: CastSink? = null

    override var active = false
        private set

    private var remote: NuvxConnect.Remote? = null
    private var remoteAt = 0L
    private var wantsPlay = false
    private var loading = false
    private var finished = false
    private var sentIds: List<String> = emptyList()
    private var queueAppendPending = false
    private var queueAppendJob: Job? = null
    private var requestedPositionMs = 0L
    private var requestedAt = 0L
    private var poll: Job? = null

    override val playWhenReady: Boolean get() = wantsPlay
    override val isPlaying: Boolean get() = active && !loading && remote?.playing == true
    override val playbackState: Int
        get() = when {
            loading -> Player.STATE_BUFFERING
            finished -> Player.STATE_ENDED
            else -> Player.STATE_READY
        }

    override val positionMs: Long
        get() {
            val r = remote
            if (r == null || loading || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) return requestedPositionMs
            if (!r.playing) return r.positionMs
            val ran = SystemClock.elapsedRealtime() - remoteAt
            return (r.positionMs + ran).let { at -> r.durationMs?.let { at.coerceAtMost(it) } ?: at }
        }

    override val durationMs: Long get() = remote?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET
    override val deviceVolume: Int get() = 0

    /** Checks whether the paired computer is reachable, finding it again by itself if its address changed. */
    fun refresh() {
        if (!NuvxConnect.available) {
            publish(available = false)
            return
        }
        scope.launch {
            val reachable = NuvxSync.healLanAddress() && withContext(Dispatchers.IO) { NuvxConnect.state() }.isSuccess
            publish(available = reachable)
        }
    }

    fun start() {
        if (active) return
        val player = localPlayer() ?: return
        if (player.mediaItemCount == 0) return
        active = true
        val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val position = player.currentPosition.coerceAtLeast(0L)
        player.pause()
        load(player.currentMediaItem, position, play = wasPlaying)
        startPolling()
        sink?.onRemoteRouteChanged()
        publish()
    }

    fun stop(resumeHere: Boolean) {
        if (!active) return
        val position = positionMs
        val wasPlaying = wantsPlay
        val playingId = remote?.id
        send(NuvxConnect.action("pause"))
        active = false
        poll?.cancel()
        queueAppendJob?.cancel()
        queueAppendJob = null
        queueAppendPending = false
        remote = null
        loading = false
        finished = false
        sentIds = emptyList()
        wantsPlay = false
        localPlayer()?.let { player ->
            val index = playingId?.let { indexOf(player, it) }
            if (index != null) player.seekTo(index, position) else player.seekTo(position)
            if (resumeHere && wasPlaying) player.play()
        }
        sink?.onRemoteRouteChanged()
        publish()
    }

    override fun play() {
        if (!active) return
        wantsPlay = true
        if (finished) {
            finished = false
            localPlayer()?.currentMediaItem?.let { load(it, 0L, play = true) }
            return
        }
        send(NuvxConnect.action("play"))
        onChanged()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        send(NuvxConnect.action("pause"))
        onChanged()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        send(NuvxConnect.seek(requestedPositionMs))
        onChanged()
    }

    override fun setVolume(fraction: Float) = Unit

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        if (item.mediaId == remote?.id && !loading) return
        load(item, 0L, play = wantsPlay || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
    }

    override fun onLocalQueueChanged() {
        if (!active || loading || queueAppendPending || remote?.capabilities?.contains("enqueue") != true) return
        val player = localPlayer() ?: return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return
        val ids = (currentIndex until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val appendedId = NuvxConnect.appendedItem(sentIds, ids) ?: return
        val added = player.getMediaItemAt(player.mediaItemCount - 1)
        val command = NuvxConnect.enqueue(
            appendedId,
            added.mediaMetadata.title?.toString().orEmpty(),
            added.mediaMetadata.artist?.toString().orEmpty(),
            added.mediaMetadata.artworkUri?.toString(),
        ) ?: return

        // One network mutation at a time. A newer or reordered phone queue must
        // never be acknowledged as the same append.
        val before = sentIds
        queueAppendPending = true
        queueAppendJob = scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { NuvxConnect.send(command) }.isSuccess
                if (sent && active && sentIds == before) {
                    sentIds = ids
                }
            } finally {
                queueAppendPending = false
            }
        }
    }

    override fun onLocalRepeatChanged(repeatMode: Int) = Unit

    override fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    private fun load(item: MediaItem?, positionMs: Long, play: Boolean) {
        val player = localPlayer() ?: return
        item ?: return
        val index = indexOf(player, item.mediaId) ?: player.currentMediaItemIndex
        val last = (index + AHEAD).coerceAtMost(player.mediaItemCount - 1)
        val picked = (index..last).map { player.getMediaItemAt(it) }
            .takeWhile { NuvxConnect.item(it.mediaId, "", "") != null }
        if (picked.isEmpty()) {
            // A local file or another source: the computer has no way to play it, so it stays on the phone.
            stop(resumeHere = play)
            return
        }
        val tracks = picked.mapNotNull {
            NuvxConnect.item(
                it.mediaId,
                it.mediaMetadata.title?.toString().orEmpty(),
                it.mediaMetadata.artist?.toString().orEmpty(),
                it.mediaMetadata.artworkUri?.toString(),
            )
        }
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = positionMs
        requestedAt = SystemClock.elapsedRealtime()
        sentIds = picked.map { it.mediaId }
        send(NuvxConnect.load(tracks, positionMs, paused = !play))
        onChanged()
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            var misses = 0
            while (isActive && active) {
                val result = withContext(Dispatchers.IO) { NuvxConnect.state() }
                result.onSuccess {
                    misses = 0
                    onRemote(it)
                }
                result.onFailure {
                    // A few missed polls are Wi-Fi hiccups; after that the computer is gone and playback comes back here.
                    if (++misses >= MAX_MISSES) {
                        if (!NuvxSync.healLanAddress()) stop(resumeHere = false)
                        misses = 0
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun onRemote(r: NuvxConnect.Remote) {
        if (!active) return
        remote = r
        remoteAt = SystemClock.elapsedRealtime()
        requestedAt = 0L
        if (loading && r.id != null && r.id in sentIds) loading = false
        if (!loading) wantsPlay = r.playing
        val player = localPlayer()
        if (!loading && player != null && r.id != null && player.currentMediaItem?.mediaId != r.id) {
            // The computer moved on to the next song it was sent: the phone's queue follows.
            indexOf(player, r.id)?.let { player.seekTo(it, 0L) }
        }
        if (!loading && r.ended) onComputerFinished()
        sink?.onRemotePlaybackChanged()
        onChanged()
        onLocalQueueChanged() // capture a single safe tail addition missed while loading
    }

    private fun onComputerFinished() {
        val player = localPlayer() ?: return
        when {
            player.hasNextMediaItem() -> {
                wantsPlay = true
                player.seekToNextMediaItem()
            }
            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> {
                wantsPlay = true
                player.seekTo(0, 0L)
            }
            else -> {
                wantsPlay = false
                finished = true
            }
        }
    }

    private fun indexOf(player: Player, mediaId: String): Int? {
        val from = player.currentMediaItemIndex.coerceAtLeast(0)
        return (from until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: (0 until from).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    }

    private fun send(command: JsonObject) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { NuvxConnect.send(command) }.isSuccess
            if (!ok && active && !NuvxSync.healLanAddress()) stop(resumeHere = false)
        }
    }

    private fun publish(available: Boolean = _state.value.available) {
        _state.value = State(available = available || active, name = NuvxSync.lanHostName, active = active)
        onChanged()
    }

    companion object {
        private const val AHEAD = 2
        private const val POLL_MS = 1_000L
        private const val MAX_MISSES = 4
        private const val SEEK_GRACE_MS = 1_500L

        private val _state = MutableStateFlow(State())
        val state = _state.asStateFlow()

        /** Set by the playback service; the player UI reaches the speaker through here. */
        @Volatile var current: ComputerSpeaker? = null
    }
}
