package com.bxane.nuvx.playback.cast

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.images.WebImage
import com.bxane.nuvx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

class CastStream(val url: String, val mimeType: String)

class CastPlayback(
    private val scope: CoroutineScope,
    private val localPlayer: () -> ExoPlayer?,
    private val resolve: suspend (MediaItem) -> CastStream?,
    private val say: (Int) -> Unit,
) : RemoteSpeaker {
    override var sink: CastSink? = null

    init {
        scope.launch {
            CastController.volume.drop(1).collect { if (active) sink?.onRemoteVolumeChanged() }
        }
    }

    override var active = false
        private set

    var resumeHereOnEnd = false

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null

    private var wantsPlay = false

    private var loading = false

    private var finished = false

    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    private var loadGeneration = 0
    private var loadJob: Job? = null
    private var reconcileJob: Job? = null
    private var reconcileAgain = false

    private var lastItemId = MediaQueueItem.INVALID_ITEM_ID

    private var endPositionMs = 0L
    private var endMediaId: String? = null
    private var endWasPlaying = false

    override val isPlaying: Boolean
        get() = active && !loading && client?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

    override val playWhenReady: Boolean get() = wantsPlay

    override val playbackState: Int
        get() = when {
            loading -> Player.STATE_BUFFERING
            finished -> Player.STATE_ENDED
            else -> when (client?.mediaStatus?.playerState) {
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        }

    override val positionMs: Long
        get() {
            val live = client?.takeIf { !loading }?.approximateStreamPosition
            val asked = requestedPositionMs
            return if (live == null || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) {
                asked
            } else {
                live.coerceAtLeast(0L)
            }
        }

    override val durationMs: Long
        get() = client?.streamDuration?.takeIf { it > 0 } ?: C.TIME_UNSET

    override val deviceVolume: Int get() = (CastController.volume.value * 100f).toInt()

    override fun setVolume(fraction: Float) = CastController.setVolume(fraction)

    override fun play() {
        if (!active) return
        wantsPlay = true
        finished = false
        client?.play()
        sink?.onRemotePlaybackChanged()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        client?.pause()
        sink?.onRemotePlaybackChanged()
    }

    override fun seekTo(positionMs: Long) {
        val client = client ?: return
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        client.seek(MediaSeekOptions.Builder().setPosition(requestedPositionMs).build())
        sink?.onRemotePlaybackChanged()
    }

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onStatus()
    }

    fun onSessionBegan(newSession: CastSession, resumed: Boolean) {
        try {
            beginSession(newSession, resumed)
        } catch (t: Throwable) {
            Log.e(TAG, "onSessionBegan threw", t)
            release()
            say(R.string.cast_connect_failed)
        }
    }

    private fun beginSession(newSession: CastSession, resumed: Boolean) {
        val remote = newSession.remoteMediaClient ?: return
        if (active && remote === client) return
        release()
        session = newSession
        client = remote
        remote.registerCallback(callback)
        active = true
        finished = false

        val player = localPlayer()
        if (player != null) {
            val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            val position = player.currentPosition.coerceAtLeast(0L)
            val currentId = player.currentMediaItem?.mediaId
            player.pause()
            val onReceiver = remote.mediaStatus?.queueItems
                ?.firstOrNull { it.itemId == remote.mediaStatus?.currentItemId }
                ?.mediaId()
            if (resumed && currentId != null && currentId == onReceiver) {
                lastItemId = remote.mediaStatus?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID
                wantsPlay = remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
                scheduleReconcile()
            } else {
                load(player.currentMediaItem, position, play = wasPlaying)
            }
        }
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
    }

    fun onSessionEnding(ending: CastSession) {
        val remote = ending.remoteMediaClient ?: client ?: return
        endPositionMs = remote.approximateStreamPosition.coerceAtLeast(0L)
        endMediaId = remote.mediaStatus?.let { status ->
            status.queueItems.firstOrNull { it.itemId == status.currentItemId }?.mediaId()
        }
        endWasPlaying = wantsPlay && remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
    }

    fun onSessionEnded(error: Int) {
        if (!active) return
        val resume = resumeHereOnEnd && endWasPlaying
        resumeHereOnEnd = false
        release()
        val player = localPlayer()
        if (player != null) {
            val index = indexOfMediaId(player, endMediaId)
            if (index != null) player.seekTo(index, endPositionMs) else player.seekTo(endPositionMs)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resume) player.play()
        }
        Log.i(TAG, "session ended: error=$error resumeHere=$resume")
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
    }

    fun onConnectFailed() = say(R.string.cast_connect_failed)

    fun release() {
        loadJob?.cancel()
        reconcileJob?.cancel()
        client?.unregisterCallback(callback)
        client = null
        session = null
        active = false
        loading = false
        wantsPlay = false
        finished = false
        lastItemId = MediaQueueItem.INVALID_ITEM_ID
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        val remote = client ?: return
        if (item.mediaId == remoteMediaId()) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) scheduleReconcile()
            return
        }
        val queued = remote.mediaStatus?.queueItems?.firstOrNull { it.mediaId() == item.mediaId }
        if (queued != null && !loading) {
            wantsPlay = true
            finished = false
            requestedPositionMs = 0L
            requestedAt = SystemClock.elapsedRealtime()
            remote.queueJumpToItem(queued.itemId, null)
            sink?.onRemotePlaybackChanged()
        } else {
            load(item, 0L, play = true)
        }
    }

    override fun onLocalQueueChanged() = scheduleReconcile()

    override fun onLocalRepeatChanged(repeatMode: Int) {
        if (!active) return
        client?.queueSetRepeatMode(receiverRepeat(repeatMode), null)
    }

    override fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    private fun onStatus() {
        val remote = client ?: return
        val status = remote.mediaStatus ?: return
        val state = status.playerState
        requestedAt = 0L

        when (state) {
            MediaStatus.PLAYER_STATE_PLAYING -> {
                wantsPlay = true
                loading = false
                finished = false
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                wantsPlay = false
                loading = false
            }
            MediaStatus.PLAYER_STATE_IDLE -> {
                if (!loading && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    wantsPlay = false
                    onReceiverFinished()
                } else if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    wantsPlay = false
                    loading = false
                    say(R.string.cast_track_unsupported)
                }
            }
        }

        val itemId = status.currentItemId
        if (itemId != MediaQueueItem.INVALID_ITEM_ID && itemId != lastItemId) {
            lastItemId = itemId
            val mediaId = status.getQueueItemById(itemId)?.mediaId()
            val player = localPlayer()
            if (mediaId != null && player != null && player.currentMediaItem?.mediaId != mediaId) {
                indexOfMediaId(player, mediaId)?.let { player.seekTo(it, 0L) }
            }
            scheduleReconcile()
        }
        sink?.onRemotePlaybackChanged()
    }

    private fun onReceiverFinished() {
        val player = localPlayer() ?: return
        when {
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> player.seekTo(0, 0L)
            else -> finished = true
        }
    }

    private fun load(item: MediaItem?, positionMs: Long, play: Boolean) {
        val remote = client ?: return
        loadJob?.cancel()
        val generation = ++loadGeneration
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = positionMs
        requestedAt = SystemClock.elapsedRealtime()
        sink?.onRemotePlaybackChanged()

        loadJob = scope.launch {
            try {
                loadNow(item, positionMs, play, generation, remote)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "load threw", t)
                loading = false
                wantsPlay = false
                say(R.string.cast_connect_failed)
                sink?.onRemotePlaybackChanged()
            }
        }
    }

    private suspend fun loadNow(item: MediaItem?, positionMs: Long, play: Boolean, generation: Int, remote: RemoteMediaClient) {
        run {
            val queueItem = item?.let { buildQueueItem(it, play) }
            if (generation != loadGeneration || !active) return@run
            if (queueItem == null) {
                loading = false
                wantsPlay = false
                if (item != null) say(R.string.cast_track_unsupported)
                sink?.onRemotePlaybackChanged()
                return@run
            }
            val repeat = receiverRepeat(localPlayer()?.repeatMode ?: Player.REPEAT_MODE_OFF)
            val request = MediaLoadRequestData.Builder()
                .setQueueData(
                    MediaQueueData.Builder()
                        .setItems(listOf(queueItem))
                        .setStartIndex(0)
                        .setRepeatMode(repeat)
                        .setStartTime(positionMs)
                        .build(),
                )
                .setAutoplay(play)
                .setCurrentTime(positionMs)
                .build()
            val result = remote.load(request).awaitResult()
            if (generation != loadGeneration || !active) return@run
            if (!result.status.isSuccess) {
                Log.w(TAG, "load failed: ${result.status.statusCode}")
                loading = false
                wantsPlay = false
                say(R.string.cast_track_unsupported)
                sink?.onRemotePlaybackChanged()
                return@run
            }
            lastItemId = remote.mediaStatus?.currentItemId ?: lastItemId
            loading = false
            sink?.onRemotePlaybackChanged()
            scheduleReconcile()
        }
    }

    private fun scheduleReconcile() {
        if (!active) return
        if (reconcileJob?.isActive == true) {
            reconcileAgain = true
            return
        }
        reconcileJob = scope.launch {
            do {
                reconcileAgain = false
                delay(RECONCILE_DELAY_MS)
                if (active && !loading) {
                    try {
                        reconcileOnce()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "reconcile threw", t)
                    }
                }
            } while (reconcileAgain && active)
        }
    }

    private suspend fun reconcileOnce() {
        val remote = client ?: return
        val player = localPlayer() ?: return
        val status = remote.mediaStatus ?: return
        val queue = status.queueItems
        val here = queue.indexOfFirst { it.itemId == status.currentItemId }
        if (here < 0) return
        val remoteAhead = queue.drop(here + 1)

        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val wanted = ((current + 1)..minOf(current + AHEAD, player.mediaItemCount - 1))
            .map { player.getMediaItemAt(it) }

        var kept = 0
        while (kept < remoteAhead.size && kept < wanted.size && remoteAhead[kept].mediaId() == wanted[kept].mediaId) {
            kept++
        }
        if (kept == remoteAhead.size && kept == wanted.size) return

        if (kept < remoteAhead.size) {
            remote.queueRemoveItems(remoteAhead.drop(kept).map { it.itemId }.toIntArray(), null).awaitResult()
        }
        for (next in wanted.drop(kept)) {
            val queueItem = buildQueueItem(next, autoplay = true) ?: break
            if (!active) return
            remote.queueInsertItems(arrayOf(queueItem), MediaQueueItem.INVALID_ITEM_ID, null).awaitResult()
        }
    }

    private suspend fun buildQueueItem(item: MediaItem, autoplay: Boolean): MediaQueueItem? {
        val stream = try {
            resolve(item)
        } catch (e: Exception) {
            Log.w(TAG, "could not resolve ${item.mediaId} for casting", e)
            null
        } ?: return null

        val metadata = item.mediaMetadata
        val music = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            metadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            metadata.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
            metadata.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            metadata.artworkUri?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(stream.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(stream.mimeType)
            .setMetadata(music)
            .setCustomData(JSONObject().put(KEY_MEDIA_ID, item.mediaId))
            .apply { metadata.durationMs?.takeIf { it > 0 }?.let(::setStreamDuration) }
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(autoplay).build()
    }

    private fun MediaQueueItem.mediaId(): String? = media?.customData?.optString(KEY_MEDIA_ID)?.ifEmpty { null }

    private fun remoteMediaId(): String? {
        val status = client?.mediaStatus ?: return null
        return status.getQueueItemById(status.currentItemId)?.mediaId()
    }

    private fun indexOfMediaId(player: Player, mediaId: String?): Int? {
        if (mediaId == null) return null
        val at = player.currentMediaItemIndex.coerceAtLeast(0)
        return (0 until player.mediaItemCount)
            .filter { player.getMediaItemAt(it).mediaId == mediaId }
            .minByOrNull { kotlin.math.abs(it - at) }
    }

    private fun receiverRepeat(repeatMode: Int): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            MediaStatus.REPEAT_MODE_REPEAT_SINGLE
        } else {
            MediaStatus.REPEAT_MODE_REPEAT_OFF
        }

    private suspend fun <R : Result> PendingResult<R>.awaitResult(): R =
        suspendCancellableCoroutine { continuation ->
            setResultCallback { continuation.resume(it) }
            continuation.invokeOnCancellation { cancel() }
        }

    companion object {
        private const val TAG = "NuvxCast"
        private const val KEY_MEDIA_ID = "mediaId"

        private const val AHEAD = 3
        private const val RECONCILE_DELAY_MS = 300L
        private const val SEEK_GRACE_MS = 1_500L

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
