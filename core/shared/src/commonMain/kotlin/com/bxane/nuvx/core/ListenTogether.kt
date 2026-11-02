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
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.Clock

const val LISTEN_TOGETHER_URL = "https://nuvx-listen.bxane.workers.dev"

@Serializable data class RoomMember(val id: String, val name: String)

@Serializable
data class SharedPlayback(
    val trackId: String? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val updatedAt: Long = 0,
    val title: String? = null,
    val artists: List<String> = emptyList(),
)

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
    data class Load(val track: Track, val positionMs: Long) : SyncAction
    data class SetPlaying(val playing: Boolean) : SyncAction
    data class Seek(val positionMs: Long) : SyncAction
}

object ListenSync {
    const val DRIFT_MS = 2_000L

    /** Server clock minus local clock, from one ping: assumes the reply was stamped halfway through the round trip. */
    fun offset(sentAt: Long, serverAt: Long, receivedAt: Long) = serverAt - (sentAt + receivedAt) / 2

    fun expected(p: SharedPlayback, serverNow: Long) = if (p.playing) p.positionMs + (serverNow - p.updatedAt).coerceAtLeast(0) else p.positionMs

    /** Guests only follow; one step per call keeps each correction observable before the next. */
    fun decide(local: PlayerState, p: SharedPlayback?, serverNow: Long): SyncAction {
        val id = p?.trackId ?: return SyncAction.None
        val target = expected(p, serverNow)
        if (local.current?.videoId != id) {
            val art = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            return SyncAction.Load(Track(id, p.title ?: "Shared track", p.artists, artworkUrl = art), target)
        }
        if (local.buffering) return SyncAction.None
        if (local.isPlaying != p.playing) return SyncAction.SetPlaying(p.playing)
        if (local.durationMs > 0 && abs(local.positionMs - target) > DRIFT_MS) return SyncAction.Seek(target)
        return SyncAction.None
    }

    /** Hosts publish on track or play-state changes, and on position jumps a listener would notice (seeks). */
    fun hostShouldPublish(last: SharedPlayback?, local: PlayerState, serverNow: Long): Boolean {
        val current = local.current ?: return last?.trackId != null
        if (last == null || last.trackId != current.videoId || last.playing != local.isPlaying) return true
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
        val pinger = launch {
            repeat(5) {
                send(Frame.Text("""{"t":"ping","c":${Clock.System.now().toEpochMilliseconds()}}"""))
                delay(300)
            }
        }
        var published: SharedPlayback? = null
        val sync = launch {
            while (true) {
                delay(1_000)
                val ui = _ui.value
                val local = player.state.value
                if (ui.isHost) {
                    if (ListenSync.hostShouldPublish(published, local, serverNow())) {
                        val t = local.current
                        published = SharedPlayback(t?.videoId, local.positionMs, local.isPlaying, serverNow(), t?.title, t?.artists.orEmpty())
                        // Built by hand: the worker rejects `null` for optional fields, which must be absent instead.
                        val out = buildJsonObject {
                            put("t", "state")
                            put("trackId", t?.videoId)
                            put("positionMs", local.positionMs.coerceAtLeast(0))
                            put("playing", local.isPlaying)
                            t?.title?.let { put("title", it.take(200)) }
                            t?.artists?.takeIf { it.isNotEmpty() }?.let { a -> put("artists", JsonArray(a.take(10).map { JsonPrimitive(it.take(100)) })) }
                        }
                        send(Frame.Text(out.toString()))
                    }
                } else when (val a = ListenSync.decide(local, ui.playback, serverNow())) {
                    is SyncAction.Load -> { player.playQueue(listOf(a.track), 0); player.seekTo(a.positionMs) }
                    is SyncAction.SetPlaying -> player.togglePlay()
                    is SyncAction.Seek -> player.seekTo(a.positionMs)
                    SyncAction.None -> Unit
                }
            }
        }
        try {
            for (frame in incoming) {
                val msg = runCatching { json.decodeFromString(ServerMsg.serializer(), (frame as? Frame.Text)?.readText() ?: continue) }.getOrNull() ?: continue
                when (msg.t) {
                    "welcome" -> _ui.update { it.copy(you = msg.you, host = msg.room?.host, members = msg.room?.members.orEmpty(), playback = msg.room?.playback, connecting = false, error = null) }
                    "members" -> _ui.update { it.copy(host = msg.host, members = msg.members.orEmpty()) }
                    "state" -> _ui.update { it.copy(playback = msg.playback) }
                    "pong" -> {
                        val now = Clock.System.now().toEpochMilliseconds()
                        val rtt = now - (msg.c ?: continue)
                        if (rtt < bestRtt) { bestRtt = rtt; offset = ListenSync.offset(msg.c, msg.s ?: continue, now) }
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
