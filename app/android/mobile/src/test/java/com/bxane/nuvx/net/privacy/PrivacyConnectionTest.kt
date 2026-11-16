package com.bxane.nuvx.net.privacy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyConnectionTest {

    private class FakeBackend(override val id: BackendId, var port: Int?, var healthy: Boolean = true) : ConnectionBackend {
        var connects = 0
        var stops = 0
        var hang = false
        var progress: List<Int> = emptyList()
        override suspend fun connect(onProgress: (Int) -> Unit): Int? {
            connects++
            if (hang) awaitCancellation()
            progress.forEach(onProgress)
            return port
        }
        override suspend fun verify(socksPort: Int) = healthy
        override suspend fun stop() { stops++ }
    }

    private fun TestScope.manager(mode: PrivacyMode, vararg backends: FakeBackend) =
        ConnectionManager(mode, backends.toList(), CoroutineScope(coroutineContext), now = { testScheduler.currentTime })

    private fun ConnectionManager.failThreeTimes() = repeat(ConnectionManager.FAILURES_TO_CHECK) { reportTransportFailure() }

    @Test fun offNeverStartsABackend() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9050)
        val m = manager(PrivacyMode.OFF, orbot)
        m.start(); advanceUntilIdle()
        assertEquals(ConnectionState.Disabled, m.state.value)
        assertEquals(0, orbot.connects)
        assertNull(m.socksPort)
    }

    @Test fun automaticPrefersAWorkingOrbot() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9150)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9150), m.state.value)
        assertEquals(0, tor.connects)
    }

    @Test fun automaticUsesEmbeddedTorWhenOrbotIsMissingOrDisconnected() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, null)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999).apply { progress = listOf(10, 100) }
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.EMBEDDED, 9999), m.state.value)
    }

    @Test fun orbotOnlyNeverSwitchesToEmbeddedTor() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, null)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.ORBOT_ONLY, orbot, tor)
        m.start(); advanceUntilIdle()
        assertTrue(m.state.value is ConnectionState.Unavailable)
        assertEquals(0, tor.connects)
        assertNull(m.socksPort)
    }

    @Test fun embeddedOnlyNeverUsesOrbot() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9050)
        val tor = FakeBackend(BackendId.EMBEDDED, null)
        val m = manager(PrivacyMode.EMBEDDED_ONLY, orbot, tor)
        m.start(); advanceUntilIdle()
        assertTrue(m.state.value is ConnectionState.Unavailable)
        assertEquals(0, orbot.connects)
    }

    @Test fun bothDownEndsUnavailableAfterBoundedRetries() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, null)
        val tor = FakeBackend(BackendId.EMBEDDED, null)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        assertEquals(ConnectionState.Unavailable("no working connection"), m.state.value)
        assertEquals(ConnectionManager.MAX_ROUNDS, orbot.connects)
        assertEquals(ConnectionManager.MAX_ROUNDS, tor.connects)
    }

    @Test fun orbotDyingMidPlaybackFailsOverToEmbeddedTor() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9050)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        orbot.healthy = false; orbot.port = null
        m.failThreeTimes(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.EMBEDDED, 9999), m.state.value)
    }

    @Test fun embeddedTorDyingFailsOverToOrbot() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, null)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        tor.healthy = false; tor.port = null; orbot.port = 9050
        m.failThreeTimes(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9050), m.state.value)
    }

    @Test fun oneBlipOrAHealthyBackendDoesNotSwitch() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9050)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        m.reportTransportFailure(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9050), m.state.value)
        m.failThreeTimes(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9050), m.state.value)
        assertEquals(0, tor.connects)
    }

    @Test fun failuresSpreadOverTimeDoNotCount() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, 9050, healthy = false)
        val tor = FakeBackend(BackendId.EMBEDDED, 9999)
        val m = manager(PrivacyMode.AUTOMATIC, orbot, tor)
        m.start(); advanceUntilIdle()
        repeat(5) { m.reportTransportFailure(); testScheduler.advanceTimeBy(ConnectionManager.FAILURE_WINDOW_MS + 1); runCurrent() }
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9050), m.state.value)
    }

    @Test fun bootstrapProgressIsReportedAndStopCancelsAHangingConnect() = runTest {
        val tor = FakeBackend(BackendId.EMBEDDED, 9999).apply { hang = true }
        val m = manager(PrivacyMode.EMBEDDED_ONLY, tor)
        m.start(); runCurrent()
        assertEquals(ConnectionState.Connecting(BackendId.EMBEDDED), m.state.value)
        m.stop()
        assertEquals(ConnectionState.Disabled, m.state.value)
        assertEquals(1, tor.stops)
    }

    @Test fun retryStartsOverAndRecovers() = runTest {
        val orbot = FakeBackend(BackendId.ORBOT, null)
        val m = manager(PrivacyMode.ORBOT_ONLY, orbot)
        m.start(); advanceUntilIdle()
        assertTrue(m.state.value is ConnectionState.Unavailable)
        orbot.port = 9050
        m.retry(); advanceUntilIdle()
        assertEquals(ConnectionState.Connected(BackendId.ORBOT, 9050), m.state.value)
    }

    @Test fun selectorIsTransparentWhenOff() {
        val selector = PrivacyProxySelector(protected = false, socksPort = { 9050 })
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://music.youtube.com/")))
    }

    @Test fun selectorBlocksInsteadOfGoingDirectWhenNothingIsConnected() {
        val selector = PrivacyProxySelector(protected = true, socksPort = { null })
        val proxy = selector.select(URI("https://music.youtube.com/")).single()
        assertEquals(PrivacyProxySelector.BLOCKED, proxy)
        assertFalse(proxy == Proxy.NO_PROXY)
    }

    @Test fun selectorSendsHostNamesThroughSocksAndKeepsLocalAddressesDirect() {
        val selector = PrivacyProxySelector(protected = true, socksPort = { 9050 })
        val proxy = selector.select(URI("https://rr1---sn.googlevideo.com/videoplayback")).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        assertEquals(9050, (proxy.address() as InetSocketAddress).port)
        assertTrue((proxy.address() as InetSocketAddress).address.isLoopbackAddress)
        for (local in listOf("http://127.0.0.1:8080/a", "http://localhost/a", "http://192.168.1.20/dav", "http://10.0.0.2/", "http://[::1]/"))
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(local)))
        for (remote in listOf("http://192.168.example.com/", "http://172.32.0.1/", "http://8.8.8.8/"))
            assertEquals(Proxy.Type.SOCKS, selector.select(URI(remote)).single().type())
    }

    @Test fun selectorReportsOnlyProxyConnectFailures() {
        var reported = 0
        val selector = PrivacyProxySelector(protected = true, socksPort = { 9050 }, onConnectFailed = { reported++ })
        selector.connectFailed(URI("https://a.example/"), InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9050), IOException())
        assertEquals(1, reported)
    }

    /** A real SOCKS5 server that records what the client asked for and tunnels to [target]. */
    private class RecordingSocks(private val target: Int) : AutoCloseable {
        val asked = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { return@thread }
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
        private fun serve(client: Socket) {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readByte(); input.skipBytes(input.readUnsignedByte())
            output.write(byteArrayOf(5, 0)); output.flush()
            input.skipBytes(3)
            val host = when (input.readUnsignedByte()) {
                3 -> "name:" + String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                1 -> "ipv4:" + ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
                else -> "other"
            }
            input.readUnsignedShort()
            asked += host
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            val upstream = Socket("127.0.0.1", target)
            thread(isDaemon = true) { runCatching { input.copyTo(upstream.getOutputStream()) }; runCatching { upstream.close() } }
            runCatching { upstream.getInputStream().copyTo(output) }
            runCatching { client.close() }
        }
        override fun close() { server.close(); worker.interrupt() }
    }

    private fun client(selector: PrivacyProxySelector) = OkHttpClient.Builder()
        .proxySelector(selector).connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    @Test fun audioRangeRequestsAndRedirectsGoThroughSocksWithoutLocalDns() {
        MockWebServer().use { web ->
            web.start()
            RecordingSocks(web.port).use { socks ->
                web.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://cdn.audio.invalid/final.m4a").build())
                web.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 100-103/1000").body("DATA").build())
                val http = client(PrivacyProxySelector(protected = true, socksPort = { socks.port }))
                val request = Request.Builder().url("http://stream.music.invalid/track.m4a").header("Range", "bytes=100-103").build()
                http.newCall(request).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("DATA", response.body.string())
                }
                // .invalid never resolves locally, so both hops can only have worked by name through the proxy.
                assertEquals(listOf("name:stream.music.invalid", "name:cdn.audio.invalid"), socks.asked.toList())
                assertEquals("bytes=100-103", web.takeRequest().headers["Range"])
                assertEquals("bytes=100-103", web.takeRequest().headers["Range"])
            }
        }
    }

    @Test fun requestsFailClosedWhenNoBackendIsConnectedOrTheProxyIsUnreachable() {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(MockResponse.Builder().body("direct").build())
            val url = "http://127.0.0.1.nip.invalid:${web.port}/"
            for (selector in listOf(
                PrivacyProxySelector(protected = true, socksPort = { null }),
                PrivacyProxySelector(protected = true, socksPort = { 1 }),
            )) {
                val failed = runCatching { client(selector).newCall(Request.Builder().url(url).build()).execute().close() }.isFailure
                assertTrue(failed)
            }
            // The same server is reachable directly, so the failures above were the block, not the network.
            client(PrivacyProxySelector(protected = false, socksPort = { null }))
                .newCall(Request.Builder().url("http://127.0.0.1:${web.port}/").build()).execute().use { assertEquals("direct", it.body.string()) }
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun behaviourIsUnchangedWhenTheFeatureIsOff() {
        MockWebServer().use { web ->
            web.start()
            web.enqueue(MockResponse.Builder().body("ok").build())
            client(PrivacyProxySelector(protected = false, socksPort = { 9 }))
                .newCall(Request.Builder().url(web.url("/x")).build()).execute().use { assertEquals("ok", it.body.string()) }
        }
    }
}
