package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.Clock

const val LISTEN_TOGETHER_URL = "https://nuvx-listen.bxane.workers.dev"

@Serializable data class RoomMember(val id: String, val name: String)
@Serializable data class SharedQueueTrack(val trackId: String, val title: String, val artists: List<String>)

@Serializable
data class SharedPlayback(
    val trackId: String? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val updatedAt: Long = 0,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    /** Remote IDs after the current track, in playback order; duplicates are intentional. */
    val queue: List<String> = emptyList(),
    val queueInfo: List<SharedQueueTrack> = emptyList(),
)

internal fun SharedPlayback.toRoomMessage() = buildJsonObject {
    put("t", "state")
    put("trackId", trackId)
    put("positionMs", positionMs)
    put("playing", playing)
    put("queue", JsonArray(queue.map(::JsonPrimitive)))
    put("queueInfo", Json.encodeToJsonElement(queueInfo))
    title?.let { put("title", it) }
    if (artists.isNotEmpty()) put("artists", JsonArray(artists.map(::JsonPrimitive)))
}

@Serializable private data class RoomSnapshot(val host: String? = null, val members: List<RoomMember> = emptyList(), val playback: SharedPlayback = SharedPlayback())

/** Every server message in one shape; fields absent from a given message stay null. */
@Serializable
private data class ServerMsg(
    val t: String,
    val you: String? = null,
    val s: Long? = null,
    val c: Long? = null,
    val room: RoomSnapshot? = null,
    val host: String? = null,
    val members: List<RoomMember>? = null,
    val playback: SharedPlayback? = null,
    val code: String? = null,
)

@Serializable private data class Created(val code: String)

data class RoomUi(
    val code: String? = null,
    val you: String? = null,
    val host: String? = null,
    val members: List<RoomMember> = emptyList(),
    val playback: SharedPlayback? = null,
    val connecting: Boolean = false,
    val error: String? = null,
) {
    val inRoom get() = code != null && you != null
    val isHost get() = inRoom && host == you
}

/** What a guest's player must do to match the host. */
sealed interface SyncAction {
    data object None : SyncAction
    data class Load(val track: Track, val positionMs: Long, val upNext: List<Track> = emptyList(), val playing: Boolean = true) : SyncAction
    data class UpdateQueue(val current: Track, val upNext: List<Track>) : SyncAction
    data class SetPlaying(val playing: Boolean) : SyncAction
    data class Seek(val positionMs: Long) : SyncAction
}

object ListenSync {
    const val DRIFT_MS = 2_000L
    private const val MAX_QUEUE = 200
    private const val MAX_QUEUE_INFO_BYTES = 65_536
    private const val SHARED_TRACK_TITLE = "Shared track"
    private val remoteId = Regex("[A-Za-z0-9_-]{11}")

    private fun Track.shareable() = !isLocal && localUri == null && remoteId.matches(videoId)

    fun snapshot(local: PlayerState, serverNow: Long): SharedPlayback {
        val current = local.current?.takeIf { it.shareable() } ?: return SharedPlayback(updatedAt = serverNow)
        val upcoming = local.queue.drop(local.index + 1).asSequence().filter { it.shareable() }.take(MAX_QUEUE).toList()
        return SharedPlayback(current.videoId, local.positionMs.coerceAtLeast(0), local.isPlaying, serverNow,
            current.title.take(200), current.artists.take(10).map { it.take(100) },
            upcoming.map(Track::videoId), boundedQueueInfo(upcoming.asSequence().map {
                SharedQueueTrack(it.videoId, it.title.take(200), it.artists.take(10).map { artist -> artist.take(100) })
            }))
    }

    private fun boundedQueueInfo(entries: Sequence<SharedQueueTrack>): List<SharedQueueTrack> {
        var bytes = 2
        val seen = mutableSetOf<String>()
        return buildList {
            for (info in entries) {
                if (size == MAX_QUEUE) break
                // A promoted guest's fallback label must not replace another device's known catalog title.
                if (info.title == SHARED_TRACK_TITLE && info.artists.isEmpty()) continue
                if (!remoteId.matches(info.trackId) || info.trackId in seen || info.title.isBlank() || info.title.length > 200 ||
                    info.artists.size > 10 || info.artists.any { it.length > 100 }) continue
                val needed = Json.encodeToJsonElement(info).toString().encodeToByteArray().size + if (isEmpty()) 0 else 1
                if (bytes + needed > MAX_QUEUE_INFO_BYTES) continue
                bytes += needed
                seen += info.trackId
                add(info)
            }
        }
    }

    /** Server clock minus local clock, from one ping: assumes the reply was stamped halfway through the round trip. */
    fun offset(sentAt: Long, serverAt: Long, receivedAt: Long) = serverAt - (sentAt + receivedAt) / 2

    fun shouldApplyClockSample(sentAt: Long, roundStartedAt: Long, roundTripMs: Long, bestRoundTripMs: Long) =
        sentAt >= roundStartedAt && roundTripMs >= 0 && roundTripMs < bestRoundTripMs

    fun expected(p: SharedPlayback, serverNow: Long) = if (p.playing) p.positionMs + (serverNow - p.updatedAt).coerceAtLeast(0) else p.positionMs

    /** Guests only follow; one step per call keeps each correction observable before the next. */
    fun decide(local: PlayerState, p: SharedPlayback?, serverNow: Long, trackWasCleared: Boolean = false): SyncAction {
        val playback = p ?: return SyncAction.None
        val id = playback.trackId ?: return if (trackWasCleared && local.isPlaying) SyncAction.SetPlaying(false) else SyncAction.None
        if (!remoteId.matches(id)) return SyncAction.None
        val details = boundedQueueInfo(playback.queueInfo.asSequence().filter { it.trackId in playback.queue }).associateBy { it.trackId }
        val upNext = playback.queue.asSequence().filter(remoteId::matches).take(MAX_QUEUE).map { queuedId ->
            val known = local.queue.firstOrNull { it.videoId == queuedId && it.shareable() } ?: sharedTrack(queuedId)
            details[queuedId]?.let { known.copy(title = it.title, artists = it.artists.ifEmpty { known.artists }) } ?: known
        }.toList()
        val target = expected(playback, serverNow)
        val current = local.current
        if (current == null || current.videoId != id || !current.shareable()) {
            return SyncAction.Load(sharedTrack(id).copy(title = p.title ?: SHARED_TRACK_TITLE, artists = p.artists), target, upNext, p.playing)
        }
        if (local.buffering) return SyncAction.None
        if (local.isPlaying != p.playing) return SyncAction.SetPlaying(p.playing)
        if (local.durationMs > 0 && abs(local.positionMs - target) > DRIFT_MS) return SyncAction.Seek(target)
        val info = current.copy(title = p.title ?: current.title, artists = p.artists.ifEmpty { current.artists })
        if (info != current || local.queue.drop(local.index + 1) != upNext) {
            return SyncAction.UpdateQueue(info, upNext)
        }
        return SyncAction.None
    }

    private fun sharedTrack(id: String) = Track(id, SHARED_TRACK_TITLE, artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg")

    internal fun apply(player: NuvxPlayer, action: SyncAction) {
        when (action) {
            is SyncAction.Load -> player.playQueue(listOf(action.track) + action.upNext, 0, action.positionMs, action.playing)
            is SyncAction.UpdateQueue -> player.syncQueue(action.current, action.upNext)
            is SyncAction.SetPlaying -> player.togglePlay()
            is SyncAction.Seek -> player.seekTo(action.positionMs)
            SyncAction.None -> Unit
        }
    }

    /** Hosts publish on track or play-state changes, and on position jumps a listener would notice (seeks). */
    fun hostShouldPublish(last: SharedPlayback?, local: PlayerState, serverNow: Long): Boolean {
        val current = snapshot(local, serverNow)
        if (current.trackId == null) return last?.trackId != null || last?.queue?.isNotEmpty() == true
        if (last == null || last.trackId != current.trackId || last.playing != current.playing || last.queue != current.queue ||
            last.title != current.title || last.artists != current.artists || last.queueInfo != current.queueInfo) return true
        return abs(expected(last, serverNow) - local.positionMs) > DRIFT_MS
    }
}

/**
 * Listen Together client for the nuvx worker (core/worker/README.md). Rooms carry track IDs and play state only;
 * each device streams the audio through its own engine. Player calls run on [scope]'s dispatcher (the UI thread).
 */
class ListenTogether(
    private val player: NuvxPlayer,
    private val scope: CoroutineScope,
    private val baseUrl: String = LISTEN_TOGETHER_URL,
    private val http: HttpClient = nuvxHttpClient().config { install(WebSockets) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(RoomUi())
    val ui: StateFlow<RoomUi> = _ui.asStateFlow()
    private var offset = 0L
    private var job: Job? = null

    private fun serverNow() = Clock.System.now().toEpochMilliseconds() + offset

    fun create(name: String) = connect(name, null)

    fun join(code: String, name: String) = connect(name, code.trim().uppercase())

    fun leave() {
        job?.cancel()
        job = null
        _ui.value = RoomUi()
    }

    private fun connect(name: String, code: String?) {
        job?.cancel()
        _ui.value = RoomUi(connecting = true)
        job = scope.launch {
            try {
                val room = code ?: http.post("$baseUrl/rooms").let { r ->
                    if (!r.status.isSuccess()) error(if (r.status.value == 429) "Too many rooms created. Try again in a minute." else "Could not create a room.")
                    r.body<Created>().code
                }
                require(Regex("^[A-Z2-9]{6}$").matches(room)) { "Room codes are 6 letters and numbers." }
                val ws = baseUrl.replaceFirst("http", "ws")
                http.webSocket("$ws/rooms/$room/ws", request = { parameter("name", name) }) {
                    _ui.update { it.copy(code = room) }
                    session()
                }
                _ui.update { it.copy(error = it.error ?: "Disconnected from the room.", you = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = RoomUi(error = e.message?.takeIf { it.length < 120 } ?: "The room is unavailable. Check the code and your connection.")
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.session() {
        // Clock sync: keep the lowest-latency of a few pings.
        var bestRtt = Long.MAX_VALUE
        var clockRoundStartedAt = Long.MIN_VALUE
        val pinger = launch {
            while (true) {
                // A join-time offset can become stale if a device adjusts its clock during a long room.
                clockRoundStartedAt = Clock.System.now().toEpochMilliseconds()
                bestRtt = Long.MAX_VALUE
                repeat(5) {
                    send(Frame.Text("""{"t":"ping","c":${Clock.System.now().toEpochMilliseconds()}}"""))
                    delay(300)
                }
                delay(30_000)
            }
        }
        var published: SharedPlayback? = null
        val receivedPlaybackUpdate = MutableStateFlow(false)
        val sync = launch(scope.coroutineContext.minusKey(Job)) {
            while (true) {
                delay(1_000)
                val ui = _ui.value
                val local = player.state.value
                if (ui.isHost) {
                    if (ListenSync.hostShouldPublish(published, local, serverNow())) {
                        val snapshot = ListenSync.snapshot(local, serverNow())
                        published = snapshot
                        send(Frame.Text(snapshot.toRoomMessage().toString()))
                    }
                } else ListenSync.apply(player, ListenSync.decide(local, ui.playback, serverNow(), receivedPlaybackUpdate.value && ui.playback?.trackId == null))
            }
        }
        try {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.takeIf { it.data.size <= 131_072 }?.readText() ?: continue
                val msg = runCatching { json.decodeFromString(ServerMsg.serializer(), text) }.getOrNull() ?: continue
                when (msg.t) {
                    "welcome" -> _ui.update { it.copy(you = msg.you, host = msg.room?.host, members = msg.room?.members.orEmpty(), playback = msg.room?.playback, connecting = false, error = null) }
                    "members" -> _ui.update { it.copy(host = msg.host, members = msg.members.orEmpty()) }
                    "state" -> { receivedPlaybackUpdate.value = true; _ui.update { it.copy(playback = msg.playback) } }
                    "pong" -> {
                        val sentAt = msg.c ?: continue
                        val serverAt = msg.s ?: continue
                        val now = Clock.System.now().toEpochMilliseconds()
                        val rtt = now - sentAt
                        if (ListenSync.shouldApplyClockSample(sentAt, clockRoundStartedAt, rtt, bestRtt)) {
                            bestRtt = rtt
                            offset = ListenSync.offset(sentAt, serverAt, now)
                        }
                    }
                    "error" -> _ui.update { it.copy(error = ERRORS[msg.code] ?: "Something went wrong in the room.", connecting = false) }
                }
            }
        } finally {
            pinger.cancel(); sync.cancel()
        }
    }


    private companion object {
        val ERRORS = mapOf(
            "room_full" to "This room is full.",
            "rate_limited" to "Slow down — too many updates.",
            "not_host" to "Only the host controls playback.",
            "bad_message" to null,
        )
    }
}
