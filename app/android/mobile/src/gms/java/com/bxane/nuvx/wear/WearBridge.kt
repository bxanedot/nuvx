package com.bxane.nuvx.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
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
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.AudioRouting
import com.bxane.nuvx.playback.DirectSourceGate
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.playback.cast.RemoteSpeaker
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
