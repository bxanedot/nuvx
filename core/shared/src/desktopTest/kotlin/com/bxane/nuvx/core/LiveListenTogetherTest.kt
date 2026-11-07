package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory dispatcher regression plus explicitly gated Worker integrations. */
class LiveListenTogetherTest {
    private class FakePlayer : NuvxPlayer {
        override val state = MutableStateFlow(PlayerState())
        override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) { state.value = PlayerState(tracks, startIndex, isPlaying = autoplay, positionMs = initialPositionMs, durationMs = 300_000) }
        override fun enqueue(tracks: List<Track>) { state.value = state.value.copy(queue = state.value.queue + tracks) }
        override fun syncQueue(current: Track, upNext: List<Track>) { state.value.syncingQueue(current, upNext)?.let { state.value = it } }
        override fun togglePlay() { state.value = state.value.copy(isPlaying = !state.value.isPlaying) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun next() = Unit
        override fun previous() = Unit
        override fun skipTo(index: Int) = Unit
    }

    @Test fun guestSyncUsesOwnerThreadAndStopsAfterLeave() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nuvx-room-owner") }.asCoroutineDispatcher()
        val ownerJob = SupervisorJob()
        val scope = CoroutineScope(ownerJob + dispatcher)
        val socketJob = SupervisorJob()
        val socket = TestSocket(SupervisorJob(socketJob) + Dispatchers.Default)
        val reconnectedSocket = TestSocket(SupervisorJob(socketJob) + Dispatchers.Default)
        val sockets = Channel<TestSocket>(2).also {
            it.trySend(socket).getOrThrow()
            it.trySend(reconnectedSocket).getOrThrow()
        }
        val engine = object : MockEngine(MockEngineConfig().apply {
            addHandler {
                val connection = sockets.receive()
                HttpResponseData(HttpStatusCode.SwitchingProtocols, GMTDate(), Headers.build {
                    append(HttpHeaders.Connection, "Upgrade")
                    append(HttpHeaders.Upgrade, "websocket")
                }, HttpProtocolVersion.HTTP_1_1, connection, connection.coroutineContext)
            }
        }) {
            override val supportedCapabilities = super.supportedCapabilities + WebSocketCapability
        }
        val http = HttpClient(engine) { install(WebSockets) }
        val fake = FakePlayer()
        val accessThreads = CopyOnWriteArrayList<Thread>()
        val reads = AtomicInteger()
        val player = object : NuvxPlayer by fake {
            override val state get() = fake.state.also {
                accessThreads += Thread.currentThread()
                reads.incrementAndGet()
            }
            override fun playQueue(tracks: List<Track>, startIndex: Int, initialPositionMs: Long, autoplay: Boolean) {
                accessThreads += Thread.currentThread()
                fake.playQueue(tracks, startIndex, initialPositionMs, autoplay)
            }
            override fun seekTo(positionMs: Long) {
                accessThreads += Thread.currentThread()
                fake.seekTo(positionMs)
            }
            override fun togglePlay() {
                accessThreads += Thread.currentThread()
                fake.togglePlay()
            }
        }
        val room = ListenTogether(player, scope, "https://room.test", http)
        try {
            socket.incoming.send(Frame.Text("""{"t":"welcome","you":"guest","room":{"host":"host","playback":{"trackId":"aaaaaaaaaaa","positionMs":12000,"playing":false}}}"""))
            val ownerThread = withContext(dispatcher) {
                room.join("ABCDEF", "Guest")
                Thread.currentThread()
            }
            val loaded = withTimeout(8_000) { fake.state.first { it.current != null } }
            assertEquals(12_000, loaded.positionMs)
            assertEquals(false, loaded.isPlaying)
            socket.incoming.send(Frame.Text("""{"t":"state","playback":{"trackId":"aaaaaaaaaaa","positionMs":22000,"playing":false}}"""))
            withTimeout(8_000) { fake.state.first { it.positionMs == 22_000L } }
            val updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            socket.incoming.send(Frame.Text("""{"t":"state","playback":{"trackId":"aaaaaaaaaaa","positionMs":22000,"playing":true,"updatedAt":$updatedAt}}"""))
            withTimeout(8_000) { fake.state.first { it.isPlaying } }
            withContext(dispatcher) {
                assertEquals(setOf(ownerThread), accessThreads.toSet(), "player reads and commands must use the owner's dispatcher")
                room.leave()
            }
            val readsAfterLeave = reads.get()
            delay(1_200)
            assertEquals(readsAfterLeave, reads.get(), "leaving must stop sync polling, not just clear the room UI")
            assertTrue(ownerJob.isActive, "leaving a room must not cancel its owner's scope")
            reconnectedSocket.incoming.send(Frame.Text("""{"t":"welcome","you":"guest","room":{"host":"host","playback":{"trackId":"bbbbbbbbbbb","positionMs":18000,"playing":false}}}"""))
            withContext(dispatcher) { room.join("BCDEFG", "Guest") }
            val reconnected = withTimeout(8_000) { fake.state.first { it.current?.videoId == "bbbbbbbbbbb" } }
            assertEquals(18_000, reconnected.positionMs)
            assertEquals(false, reconnected.isPlaying)
            withContext(dispatcher) {
                assertEquals(setOf(ownerThread), accessThreads.toSet())
                room.leave()
            }
        } finally {
            room.leave()
            ownerJob.cancelAndJoin()
            socketJob.cancelAndJoin()
            sockets.close()
            http.close()
            dispatcher.close()
        }
    }

    private class TestSocket(override val coroutineContext: CoroutineContext) : DefaultWebSocketSession {
        override var masking = false
        override var maxFrameSize = 1_048_576L
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions = emptyList<WebSocketExtension<*>>()
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 15_000L
        override val closeReason = CompletableDeferred<CloseReason?>()
        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun flush() = Unit
        override fun terminate() { incoming.close(); outgoing.close(); closeReason.complete(null) }
    }

    @Test fun guestReceivesUpcomingNamesFromLocalWorkerAndMetadataOnlyEdits() = runBlocking {
        val base = System.getenv("NUVX_LISTEN_TEST_URL")
        assumeTrue("Set NUVX_LIVE=1 and NUVX_LISTEN_TEST_URL for the local Worker integration", System.getenv("NUVX_LIVE") == "1" && base != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val current = Track("aaaaaaaaaaa", "Current", listOf("Current artist"))
            val next = Track("bbbbbbbbbbb", "Upcoming", listOf("First artist", "Second artist"))
            val hostPlayer = FakePlayer().apply {
                playQueue(listOf(current, next, Track("local:private", "Private", localUri = "file:///private.mp3"), next), 0)
                togglePlay()
                seekTo(12_000)
            }
            val host = ListenTogether(hostPlayer, scope, requireNotNull(base)).apply { create("Host") }
            val code = withTimeout(15_000) { host.ui.first { it.inRoom }.code!! }
            val guestPlayer = FakePlayer()
            val guest = ListenTogether(guestPlayer, scope, base).apply { join(code, "Guest") }
            val received = withTimeout(15_000) { guestPlayer.state.first { !it.isPlaying && it.queue.size == 3 && it.queue[1].title == "Upcoming" } }
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "bbbbbbbbbbb"), received.queue.map(Track::videoId))
            assertEquals(next.artists, received.queue[2].artists)
            assertEquals(12_000, received.positionMs)
            hostPlayer.state.value = hostPlayer.state.value.copy(queue = listOf(current, next.copy(title = "Updated upcoming")))
            val edited = withTimeout(15_000) { guestPlayer.state.first { it.queue.size == 2 && it.queue[1].title == "Updated upcoming" } }
            assertEquals(12_000, edited.positionMs)
            assertEquals(false, edited.isPlaying)
            host.leave()
            guest.leave()
        } finally {
            scope.cancel()
        }
    }

    @Test fun guestFollowsHost() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact the Listen Together worker", System.getenv("NUVX_LIVE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hostPlayer = FakePlayer().apply { playQueue(listOf(Track("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"))), 0); seekTo(42_000) }
            val host = ListenTogether(hostPlayer, scope).apply { create("Host") }
            val code = kotlinx.coroutines.withTimeoutOrNull(15_000) { host.ui.first { it.inRoom }.code!! } ?: error("host never joined: ${host.ui.value}")
            val guestPlayer = FakePlayer()
            val guest = ListenTogether(guestPlayer, scope).apply { join(code, "Guest") }
            withTimeout(15_000) { guest.ui.first { it.members.size == 2 } }
            val synced = withTimeout(15_000) { guestPlayer.state.first { it.current?.videoId == "dQw4w9WgXcQ" && it.positionMs >= 42_000 } }
            assertEquals("Never Gonna Give You Up", synced.current?.title)
            println("guest synced to ${synced.current?.title} @ ${synced.positionMs} ms in room $code")
            hostPlayer.togglePlay()
            withTimeout(15_000) { guestPlayer.state.first { !it.isPlaying } }
            val next = Track("aaaaaaaaaaa", "Next")
            val later = Track("bbbbbbbbbbb", "Later")
            hostPlayer.enqueue(listOf(next, Track("local:private", "Private", localUri = "file:///private.mp3"), later, next))
            val queued = withTimeout(15_000) { guestPlayer.state.first { it.queue.drop(1).map(Track::videoId) == listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa") } }
            assertEquals("dQw4w9WgXcQ", queued.current?.videoId)
            assertEquals(false, queued.isPlaying)
            assertEquals(synced.positionMs, queued.positionMs)
            hostPlayer.state.value = hostPlayer.state.value.copy(queue = listOf(hostPlayer.state.value.current!!))
            withTimeout(15_000) { guestPlayer.state.first { it.queue.size == 1 } }
            println("guest paused with host")
            host.leave()
            withTimeout(15_000) { guest.ui.first { it.isHost } }
            println("guest became host after host left")
            guest.leave()
        } finally {
            scope.cancel()
        }
    }
}
