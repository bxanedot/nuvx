package com.bxane.nuvx.data.listentogether

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PartySocketConnectionTest {
    @Test
    fun serverCloseCodesReconnectWithoutOverlappingSockets() = runBlocking {
        val attempts = AtomicInteger()
        val openSessions = AtomicInteger()
        val maxOpenSessions = AtomicInteger()
        val closeCodes = ConcurrentLinkedQueue<Int>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val attempt = attempts.incrementAndGet()
                if (attempt > 2) return MockResponse().setResponseCode(503)
                val closeCode = if (attempt == 1) 1012 else 1001
                return MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val current = openSessions.incrementAndGet()
                            maxOpenSessions.updateAndGet { maxOf(it, current) }
                            webSocket.close(closeCode, "service restart")
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            closeCodes.add(code)
                            openSessions.decrementAndGet()
                        }
                    },
                )
            }
        }
        server.start()
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val job = launch(Dispatchers.IO) { connection(server, http).run() }

        try {
            withTimeout(10_000) {
                while (attempts.get() < 3 || closeCodes.size < 2 || openSessions.get() != 0) delay(10)
            }

            assertEquals(3, attempts.get())
            assertEquals(1, maxOpenSessions.get())
            assertEquals(listOf(1012, 1001), closeCodes.toList())
        } finally {
            job.cancelAndJoin()
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun repeatedHandshakeFailuresBackOffAndStopAfterCancellation() = runBlocking {
        val attempts = AtomicInteger()
        val requestTimes = ConcurrentLinkedQueue<Long>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestTimes.add(System.nanoTime())
                attempts.incrementAndGet()
                return MockResponse().setResponseCode(503)
            }
        }
        server.start()
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val job = launch(Dispatchers.IO) { connection(server, http).run() }

        try {
            withTimeout(10_000) {
                while (attempts.get() < 3) delay(10)
            }
            val times = requestTimes.toList()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(times[1] - times[0]) >= 450)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(times[2] - times[1]) >= 900)

            job.cancelAndJoin()
            val stoppedAt = attempts.get()
            delay(6_200)
            assertEquals(stoppedAt, attempts.get())
        } finally {
            job.cancelAndJoin()
            http.close()
            server.shutdown()
        }
    }

    private fun connection(server: MockWebServer, http: HttpClient) = PartySocketConnection(
        scope = CoroutineScope(Dispatchers.IO),
        http = http,
        partyCode = { "ABC123" },
        token = { "test-token" },
        state = { ListenTogether.State() },
        updateState = {},
        clock = ServerClock { 0L },
        socketBase = { server.url("/").toString().removeSuffix("/").replace("http", "ws") },
        setSession = {},
        onFrame = {},
        partyPositionMs = { null },
        redact = { it.orEmpty() },
        tag = "PartySocketConnectionTest",
    )
}
