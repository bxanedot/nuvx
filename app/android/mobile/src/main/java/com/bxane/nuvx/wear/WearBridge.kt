package com.bxane.nuvx.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.flow.update
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.bxane.nuvx.data.model.NOTIFICATION_ART_PX
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.player.artworkMeshColors
import com.bxane.nuvx.widget.MediaWidgetSnapshot
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.android.gms.wearable.ChannelClient
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.AudioRouting
import com.bxane.nuvx.playback.DirectSourceGate
import com.bxane.nuvx.playback.toMediaItem
import com.bxane.nuvx.playback.toSong
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.playback.cast.CastSink
import com.bxane.nuvx.playback.cast.RemoteSpeaker
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.bxane.nuvx.wear.WearProtocol as P

object WearBridge {

    private const val TAG = "NuvxWear"

    private const val COALESCE_MS = 120L

    private const val QUEUE_ART_ROWS = 12

    private const val QUEUE_AHEAD = 40

    private class Playing(
        val snapshot: MediaWidgetSnapshot,
        val album: String?,
        val playing: Boolean,
        val advancing: Boolean,
        val positionMs: Long,
        val positionAt: Long,
        val durationMs: Long,
        val speed: Float,
        val repeatMode: Int,
    )

    private class Art(val url: String, val asset: Asset, val colors: ArrayList<Int>)

    internal class QueueRow(val index: Int, val title: String, val artist: String, val artworkUrl: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var last: Playing? = null
    @Volatile private var art: Art? = null

    @Volatile private var watchReachable: Boolean? = null
    @Volatile private var listening = false
    private var flushJob: Job? = null

    internal fun publish(context: Context, snapshot: MediaWidgetSnapshot, player: Player, playing: Boolean?) {
        last = Playing(
            snapshot = snapshot,
            album = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString(),
            playing = playing ?: player.playWhenReady,
            advancing = playing != false && player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            positionAt = System.currentTimeMillis(),
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            repeatMode = player.repeatMode,
        )
        schedule(context.applicationContext)
    }

    internal fun republish(context: Context, player: Player) {
        val previous = last ?: return
        publish(context, previous.snapshot, player, null)
    }

    internal fun onWatchSeen(context: Context) {
        watchReachable = true
        ensureListening(context.applicationContext)
    }

    internal fun sync(context: Context) {
        val app = context.applicationContext
        if (last == null) {
            val saved = MediaWidgetSnapshot.load(app)
            if (saved.hasTrack) {
                last = Playing(
                    snapshot = saved,
                    album = null,
                    playing = false,
                    advancing = false,
                    positionMs = 0L,
                    positionAt = System.currentTimeMillis(),
                    durationMs = 0L,
                    speed = 1f,
                    repeatMode = AppSettings.repeatMode.value,
                )
            }
        }
        scope.launch { flush(app) }
    }

    internal fun adjustVolume(context: Context, steps: Int) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        schedule(app)
    }

    internal fun publishQueue(context: Context, rows: List<QueueRow>, currentIndex: Int, currentMediaId: String?) {
        val app = context.applicationContext
        val request = PutDataMapRequest.create(P.PATH_QUEUE)
        val items = ArrayList<DataMap>(rows.size)
        rows.forEachIndexed { position, row ->
            items += DataMap().apply {
                putInt(P.KEY_INDEX, row.index)
                putString(P.KEY_TITLE, row.title)
                putString(P.KEY_ARTIST, row.artist)
                if (position < QUEUE_ART_ROWS) {
                    runBlocking { loadBitmap(app, row.artworkUrl, ROW_ART_PX, P.ROW_ART_PX) }
                        ?.let { putAsset(P.KEY_ART, Asset.createFromBytes(it.jpeg(80))) }
                }
            }
        }
        request.dataMap.apply {
            putDataMapArrayList(P.KEY_ITEMS, items)
            putInt(P.KEY_CURRENT_INDEX, currentIndex)
            putString(P.KEY_QUEUE_MEDIA_ID, currentMediaId.orEmpty())
            putLong(P.KEY_POSITION_AT, System.currentTimeMillis())
        }
        put(app, request)
    }

    internal val queueAhead get() = QUEUE_AHEAD

    private val _watchSession = MutableStateFlow(false)

    val watchSession: StateFlow<Boolean> = _watchSession.asStateFlow()

    @Volatile private var streams: DataSource.Factory? = null

    fun attachStreams(factory: DataSource.Factory?) {
        streams = factory
    }

    private val _watchOutput = MutableStateFlow(WatchOutputState())

    val watchOutput: StateFlow<WatchOutputState> = _watchOutput.asStateFlow()

    private fun onWatchNodes(nodes: Set<Node>) {
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        watchNode = node?.id ?: watchNode
        _watchOutput.update { it.copy(available = node != null, name = node?.displayName ?: it.name) }
    }

    fun refreshWatchOutput(context: Context) {
        val app = context.applicationContext
        scope.launch {
            watchReachable = null
            isWatchReachable(app)
        }
    }

    fun selectWatchOutput() {
        speaker?.start()
    }

    fun leaveWatchOutput() {
        speaker?.stop(resumeHere = true)
    }

    fun setWatchVolume(fraction: Float) {
        speaker?.setVolume(fraction)
    }

    internal fun onSpeakerChanged(speaker: WatchSpeaker) {
        val volume = speaker.watchVolume?.let { (level, max) -> level.toFloat() / max } ?: 0f
        _watchOutput.update { it.copy(active = speaker.active, volume = volume) }
    }

    @Volatile internal var speaker: WatchSpeaker? = null
        private set

    fun createSpeaker(
        context: Context,
        scope: CoroutineScope,
        localPlayer: () -> ExoPlayer?,
        onChanged: () -> Unit,
    ): RemoteSpeaker = WatchSpeaker(context.applicationContext, localPlayer, onChanged).also { speaker = it }

    fun releaseSpeaker() {
        speaker?.takeIf { it.active }?.stop(resumeHere = false)
        speaker = null
    }

    internal fun setWatchSession(active: Boolean) {
        _watchSession.value = active
    }

    @Volatile private var watchNode: String? = null

    internal fun sendToWatch(context: Context, path: String, payload: ByteArray, result: (Boolean) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            repeat(2) {
                val node = watchNode ?: runCatching {
                    Tasks.await(
                        Wearable.getCapabilityClient(app).getCapability(P.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                        5, TimeUnit.SECONDS,
                    ).nodes.let { nodes -> (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id }
                }.getOrNull()?.also { watchNode = it }
                if (node != null) {
                    val sent = runCatching {
                        Tasks.await(Wearable.getMessageClient(app).sendMessage(node, path, payload), 10, TimeUnit.SECONDS)
                    }.isSuccess
                    if (sent) return@launch result(true)
                }
                watchNode = null
            }
            result(false)
        }
    }

    internal fun publishOutputs(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val request = PutDataMapRequest.create(P.PATH_OUTPUTS)
        request.dataMap.apply {
            putDataMapArrayList(
                P.KEY_OUTPUTS,
                AudioRouting.outputs(audio).mapTo(ArrayList()) { d ->
                    DataMap().apply {
                        putInt(P.KEY_OUTPUT_ID, d.id)
                        putString(P.KEY_OUTPUT_NAME, d.name)
                        putString(P.KEY_OUTPUT_KIND, d.kind.name)
                        putBoolean(P.KEY_OUTPUT_ACTIVE, d.isActive && speaker?.active != true)
                    }
                },
            )
            putBoolean(P.KEY_WATCH_SPEAKER, speaker?.active == true)
            putLong(P.KEY_POSITION_AT, System.currentTimeMillis())
        }
        scope.launch { put(app, request) }
    }

    private val streamPool = Executors.newCachedThreadPool()

    internal fun serveStream(context: Context, channel: ChannelClient.Channel, ensureService: () -> Unit) {
        val app = context.applicationContext
        streamPool.execute {
            val client = Wearable.getChannelClient(app)
            var source: DataSource? = null
            try {
                val input = DataInputStream(Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS))
                val output = DataOutputStream(
                    BufferedOutputStream(Tasks.await(client.getOutputStream(channel), 10, TimeUnit.SECONDS), STREAM_BUFFER),
                )
                val uri = input.readUTF()
                val position = input.readLong()
                val length = input.readLong()
                val asked = SystemClock.elapsedRealtime()
                val factory = streams ?: run {
                    ensureService()
                    var waited = 0
                    while (streams == null && waited < 6_000) {
                        Thread.sleep(100)
                        waited += 100
                    }
                    streams
                }
                val opened = factory?.let { f ->
                    val ds = f.createDataSource().also { source = it }
                    runCatching {
                        DirectSourceGate.ungated { ds.open(
                            DataSpec.Builder()
                                .setUri(
                                    Uri.parse(uri).let { parsed ->
                                        if (parsed.scheme != "nuvx" || parsed.authority != "source") parsed else parsed.buildUpon()
                                            .appendQueryParameter(AudioCache.WATCH_READ_PARAM, "1")
                                            .build()
                                    },
                                )
                                .setPosition(position)
                                .setLength(if (length < 0) C.LENGTH_UNSET.toLong() else length)
                                .build(),
                        ) }
                    }.onFailure { Log.w(TAG, "stream open failed: ${it.message}") }.getOrNull()
                }
                val ds = source
                if (opened == null || ds == null) {
                    output.writeByte(P.STREAM_FAILED)
                    output.writeLong(-1L)
                    output.flush()
                    return@execute
                }
                output.writeByte(P.STREAM_OK)
                output.writeLong(if (opened == C.LENGTH_UNSET.toLong()) -1L else opened)
                output.flush()
                Log.d(TAG, "stream open ${SystemClock.elapsedRealtime() - asked}ms @$position ${uri.take(60)}")
                val buffer = ByteArray(STREAM_BUFFER)
                var served = 0L
                while (true) {
                    val read = ds.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        served += read
                    }
                }
                output.flush()
                Log.d(TAG, "stream done ${served / 1024}KB in ${SystemClock.elapsedRealtime() - asked}ms")
            } catch (e: Exception) {
                Log.d(TAG, "stream ended: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                runCatching { source?.close() }
                runCatching { Tasks.await(client.close(channel), 5, TimeUnit.SECONDS) }
            }
        }
    }

    private const val STREAM_BUFFER = 32 * 1024

    private fun schedule(app: Context) {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(COALESCE_MS)
                if (isWatchReachable(app)) flush(app)
            }
        }
    }

    private suspend fun flush(app: Context) {
        val now = last
        val request = PutDataMapRequest.create(P.PATH_NOW_PLAYING)
        val map = request.dataMap
        if (now == null || !now.snapshot.hasTrack) {
            map.putBoolean(P.KEY_HAS_TRACK, false)
        } else {
            val s = now.snapshot
            map.putBoolean(P.KEY_HAS_TRACK, true)
            map.putString(P.KEY_MEDIA_ID, s.mediaId.orEmpty())
            map.putString(P.KEY_TITLE, s.title)
            map.putString(P.KEY_ARTIST, s.artist)
            map.putString(P.KEY_ALBUM, now.album.orEmpty())
            map.putBoolean(P.KEY_PLAYING, now.playing)
            map.putBoolean(P.KEY_ADVANCING, now.advancing)
            map.putLong(P.KEY_POSITION_MS, now.positionMs)
            map.putLong(P.KEY_POSITION_AT, now.positionAt)
            map.putLong(P.KEY_DURATION_MS, now.durationMs)
            map.putFloat(P.KEY_SPEED, now.speed)
            map.putBoolean(P.KEY_HAS_PREVIOUS, s.hasPrevious)
            map.putBoolean(P.KEY_HAS_NEXT, s.hasNext)
            map.putBoolean(P.KEY_LIKED, s.isLiked)
            map.putBoolean(P.KEY_CAN_LIKE, s.canLike)
            map.putBoolean(P.KEY_SHUFFLE, s.shuffleEnabled)
            map.putInt(P.KEY_REPEAT, now.repeatMode)
            artworkFor(app, s.artworkUrl)?.let { cover ->
                map.putAsset(P.KEY_ART, cover.asset)
                map.putString(P.KEY_ART_ID, cover.url)
                map.putIntegerArrayList(P.KEY_COLORS, cover.colors)
            }
        }
        map.putBoolean(P.KEY_WATCH_SPEAKER, speaker?.active == true)
        val watchVolume = speaker?.watchVolume
        if (watchVolume != null) {
            map.putInt(P.KEY_VOLUME, watchVolume.first)
            map.putInt(P.KEY_VOLUME_MAX, watchVolume.second)
        } else {
            app.getSystemService(AudioManager::class.java)?.let { audio ->
                map.putInt(P.KEY_VOLUME, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                map.putInt(P.KEY_VOLUME_MAX, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
        }
        put(app, request)
    }

    private fun put(app: Context, request: PutDataMapRequest) {
        val task = Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent())
        runCatching { Tasks.await(task, 10, TimeUnit.SECONDS) }
            .onFailure { Log.w(TAG, "put ${request.uri.path} failed: ${it.message}") }
    }

    private suspend fun artworkFor(app: Context, url: String?): Art? {
        if (url.isNullOrBlank()) return null
        art?.takeIf { it.url == url }?.let { return it }
        val bitmap = loadBitmap(app, url, NOTIFICATION_ART_PX, P.ART_PX) ?: return null
        val colors = artworkMeshColors(bitmap.asImageBitmap()).mapTo(ArrayList()) { it.toArgb() }
        return Art(url, Asset.createFromBytes(bitmap.jpeg(85)), colors).also { art = it }
    }

    private suspend fun loadBitmap(app: Context, url: String?, ladderPx: Int, px: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(app)
            .data(url.artworkAt(ladderPx) ?: url)
            .size(px)
            .allowHardware(false)
            .build()
        val result = runCatching { SingletonImageLoader.get(app).execute(request) }.getOrNull()
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        val square = if (bitmap.width == bitmap.height) bitmap else Bitmap.createBitmap(
            bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side,
        )
        return if (side > px) Bitmap.createScaledBitmap(square, px, px, true) else square
    }

    private fun Bitmap.jpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun isWatchReachable(app: Context): Boolean {
        watchReachable?.let { return it }
        val nodes = runCatching {
            Tasks.await(
                Wearable.getCapabilityClient(app)
                    .getCapability(P.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                5, TimeUnit.SECONDS,
            ).nodes
        }.getOrDefault(emptySet())
        val reachable = nodes.isNotEmpty()
        watchReachable = reachable
        onWatchNodes(nodes)
        ensureListening(app)
        return reachable
    }

    private fun ensureListening(app: Context) {
        if (listening) return
        listening = true
        runCatching {
            Wearable.getCapabilityClient(app).addListener(
                { info ->
                    val reachable = info.nodes.isNotEmpty()
                    onWatchNodes(info.nodes)
                    val arrived = reachable && watchReachable != true
                    watchReachable = reachable
                    if (arrived) schedule(app)
                },
                P.CAPABILITY_WATCH,
            )
        }
    }
}



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
