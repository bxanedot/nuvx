package com.bxane.nuvx.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.playback.cast.CastSink
import com.bxane.nuvx.playback.cast.RemoteSpeaker
import com.bxane.nuvx.playback.toMediaItem
import com.bxane.nuvx.playback.toSong
import com.google.android.gms.wearable.DataMap
import kotlin.math.roundToInt
import com.bxane.nuvx.wear.WearProtocol as P

internal class WatchSpeaker(
    private val app: Context,
    private val localPlayer: () -> ExoPlayer?,
    private val onChanged: () -> Unit,
) : RemoteSpeaker {

    override var sink: CastSink? = null

    override var active = false
        private set

    private class Status(
        val mediaId: String,
        val state: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Int,
        val volumeMax: Int,
        val at: Long,
    )

    private var status: Status? = null
    private var wantsPlay = false
    private var loading = false
    private var finished = false
    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    private var onWatch: List<String> = emptyList()

    override val playWhenReady: Boolean get() = wantsPlay
    override val isPlaying: Boolean get() = active && !loading && status?.isPlaying == true

    override val playbackState: Int
        get() = when {
            loading -> Player.STATE_BUFFERING
            finished -> Player.STATE_ENDED
            status?.state == Player.STATE_BUFFERING -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

    override val positionMs: Long
        get() {
            val s = status
            if (s == null || loading || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) return requestedPositionMs
            if (!s.isPlaying) return s.positionMs
            val ran = SystemClock.elapsedRealtime() - s.at
            return (s.positionMs + ran).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
        }

    override val durationMs: Long get() = status?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET

    override val deviceVolume: Int
        get() = status?.takeIf { it.volumeMax > 0 }?.let { it.volume * 100 / it.volumeMax } ?: 0

    val watchVolume: Pair<Int, Int>? get() = status?.takeIf { active && it.volumeMax > 0 }?.let { it.volume to it.volumeMax }

    fun start() {
        if (active) return
        val player = localPlayer() ?: return
        if (player.mediaItemCount == 0) return
        active = true
        WearBridge.setWatchSession(true)
        AppSettings.streamCeilingOverride.value = AudioQuality.MEDIUM
        val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val position = player.currentPosition.coerceAtLeast(0L)
        player.pause()
        player.stop()
        load(player.currentMediaItem, position, play = wasPlaying)
        sink?.onRemoteRouteChanged()
        changed()
    }

    fun stop(resumeHere: Boolean) {
        if (!active) return
        val s = status
        val wasPlaying = wantsPlay
        val position = positionMs
        send(P.SPEAKER_STOP)
        active = false
        WearBridge.setWatchSession(false)
        AppSettings.streamCeilingOverride.value = null
        status = null
        loading = false
        finished = false
        onWatch = emptyList()
        localPlayer()?.let { player ->
            val index = s?.mediaId?.let { indexOf(player, it) }
            if (index != null) player.seekTo(index, position) else player.seekTo(position)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resumeHere && wasPlaying) player.play()
        }
        wantsPlay = false
        sink?.onRemoteRouteChanged()
        changed()
    }

    override fun play() {
        if (!active) return
        wantsPlay = true
        if (finished) {
            finished = false
            localPlayer()?.currentMediaItem?.let { load(it, 0L, play = true) }
            return
        }
        send(P.SPEAKER_PLAY)
        changed()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        send(P.SPEAKER_PAUSE)
        changed()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        send(P.SPEAKER_SEEK, requestedPositionMs.toString())
        changed()
    }

    override fun setVolume(fraction: Float) {
        val max = status?.volumeMax ?: return
        send(P.SPEAKER_VOLUME, "=" + (fraction.coerceIn(0f, 1f) * max).roundToInt())
    }

    fun nudgeVolume(steps: Int) {
        if (!active || steps == 0) return
        send(P.SPEAKER_VOLUME, if (steps > 0) "+$steps" else "$steps")
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        if (item.mediaId == status?.mediaId && !loading) {
            sendAhead()
            return
        }
        if (item.mediaId in onWatch && !loading) {
            finished = false
            requestedPositionMs = 0L
            requestedAt = SystemClock.elapsedRealtime()
            send(P.SPEAKER_JUMP, item.mediaId)
            changed()
        } else {
            load(item, 0L, play = wantsPlay || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
    }

    override fun onLocalQueueChanged() {
        if (active) sendAhead()
    }

    override fun onLocalRepeatChanged(repeatMode: Int) {
        if (active) send(P.SPEAKER_REPEAT, repeatMode.toString())
    }

    override fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    fun onStatus(map: DataMap) {
        if (!active) return
        val s = Status(
            mediaId = map.getString(P.KEY_MEDIA_ID).orEmpty(),
            state = map.getInt(P.KEY_STATE),
            playWhenReady = map.getBoolean(P.KEY_PLAYING),
            isPlaying = map.getBoolean(P.KEY_ADVANCING),
            positionMs = map.getLong(P.KEY_POSITION_MS),
            durationMs = map.getLong(P.KEY_DURATION_MS),
            volume = map.getInt(P.KEY_VOLUME),
            volumeMax = map.getInt(P.KEY_VOLUME_MAX),
            at = SystemClock.elapsedRealtime(),
        )
        val volumeChanged = status?.let { it.volume != s.volume } ?: true
        status = s
        requestedAt = 0L
        if (s.mediaId == onWatch.firstOrNull() || s.mediaId in onWatch) loading = false
        if (!loading) wantsPlay = s.playWhenReady

        val player = localPlayer()
        if (player != null && s.mediaId.isNotEmpty() && player.currentMediaItem?.mediaId != s.mediaId) {
            indexOf(player, s.mediaId)?.let { player.seekTo(it, 0L) }
        }
        if (!loading && s.state == Player.STATE_ENDED) onWatchFinished()
        sink?.onRemotePlaybackChanged()
        if (volumeChanged) sink?.onRemoteVolumeChanged()
        changed()
    }

    private fun onWatchFinished() {
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

    private fun load(item: MediaItem?, positionMs: Long, play: Boolean) {
        val player = localPlayer() ?: return
        item ?: return
        val index = indexOf(player, item.mediaId) ?: player.currentMediaItemIndex
        val tracks = tracksFrom(player, index)
        if (tracks.isEmpty()) return
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = positionMs
        requestedAt = SystemClock.elapsedRealtime()
        onWatch = tracks.map { it.getString(P.KEY_MEDIA_ID).orEmpty() }
        val map = DataMap().apply {
            putDataMapArrayList(P.KEY_TRACKS, tracks)
            putLong(P.KEY_POSITION_MS, positionMs)
            putBoolean(P.KEY_PLAYING, play)
            putInt(P.KEY_REPEAT, player.repeatMode)
        }
        send(P.SPEAKER_LOAD, map.toByteArray())
        changed()
    }

    private fun sendAhead() {
        val player = localPlayer() ?: return
        val playing = status?.mediaId ?: onWatch.firstOrNull() ?: return
        val index = indexOf(player, playing) ?: return
        val ahead = tracksFrom(player, index).drop(1)
        val ids = listOf(playing) + ahead.map { it.getString(P.KEY_MEDIA_ID).orEmpty() }
        if (ids == onWatch) return
        onWatch = ids
        send(P.SPEAKER_AHEAD, DataMap().apply { putDataMapArrayList(P.KEY_TRACKS, ArrayList(ahead)) }.toByteArray())
    }

    private fun tracksFrom(player: Player, index: Int): ArrayList<DataMap> {
        if (index !in 0 until player.mediaItemCount) return ArrayList()
        val last = (index + AHEAD).coerceAtMost(player.mediaItemCount - 1)
        return (index..last).mapNotNullTo(ArrayList()) { i ->
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: item.toSong().toMediaItem().localConfiguration?.uri
                ?: return@mapNotNullTo null
            DataMap().apply {
                putString(P.KEY_MEDIA_ID, item.mediaId)
                putString(P.KEY_TITLE, item.mediaMetadata.title?.toString().orEmpty())
                putString(P.KEY_ARTIST, item.mediaMetadata.artist?.toString().orEmpty())
                putString(P.KEY_URI, uri.toString())
            }
        }
    }

    private fun indexOf(player: Player, mediaId: String): Int? {
        val from = player.currentMediaItemIndex.coerceAtLeast(0)
        return (from until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: (0 until from).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    }

    private fun send(command: String, arg: String = "") = send(command, arg.toByteArray())

    private fun send(command: String, payload: ByteArray) {
        WearBridge.sendToWatch(app, "${P.PATH_SPEAKER}/$command", payload) { delivered ->
            if (!delivered) main.post { if (active) stop(resumeHere = false) }
        }
    }

    private fun changed() {
        WearBridge.onSpeakerChanged(this)
        onChanged()
    }

    private val main = Handler(Looper.getMainLooper())

    private companion object {
        const val AHEAD = 2

        const val SEEK_GRACE_MS = 1_500L
    }
}
