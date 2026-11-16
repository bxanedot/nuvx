package com.bxane.nuvx.net.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

enum class PrivacyMode { OFF, AUTOMATIC, ORBOT_ONLY, EMBEDDED_ONLY }

enum class BackendId { ORBOT, EMBEDDED }

sealed interface ConnectionState {
    data object Disabled : ConnectionState
    data class Connecting(val backend: BackendId) : ConnectionState
    data class Bootstrapping(val backend: BackendId, val percent: Int) : ConnectionState
    data class Connected(val backend: BackendId, val socksPort: Int) : ConnectionState
    data class Switching(val from: BackendId) : ConnectionState
    data class Unavailable(val reason: String) : ConnectionState
}

/** One way of reaching Tor. [connect] returns a loopback SOCKS port only after a real request went through it. */
interface ConnectionBackend {
    val id: BackendId
    suspend fun connect(onProgress: (Int) -> Unit): Int?
    suspend fun verify(socksPort: Int): Boolean
    suspend fun stop()
}

fun PrivacyMode.candidates(): List<BackendId> = when (this) {
    PrivacyMode.OFF -> emptyList()
    PrivacyMode.AUTOMATIC -> listOf(BackendId.ORBOT, BackendId.EMBEDDED)
    PrivacyMode.ORBOT_ONLY -> listOf(BackendId.ORBOT)
    PrivacyMode.EMBEDDED_ONLY -> listOf(BackendId.EMBEDDED)
}

/**
 * Keeps one verified Tor backend up for the chosen mode and fails over to the other one in Automatic.
 * It never falls back to a direct connection: with no backend the state is [ConnectionState.Unavailable]
 * and [PrivacyProxySelector] blocks protected requests.
 */
class ConnectionManager(
    private val mode: PrivacyMode,
    backends: List<ConnectionBackend>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val backends = backends.associateBy { it.id }
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disabled)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private val failures = ArrayDeque<Long>()

    /** The SOCKS port protected requests must use, or null while nothing verified is up. */
    val socksPort: Int? get() = (_state.value as? ConnectionState.Connected)?.socksPort

    fun start() {
        if (mode == PrivacyMode.OFF || job?.isActive == true) return
        job = scope.launch { connectLoop(first = null) }
    }

    /** User pressed Retry, or the network changed: start over from the preferred backend. */
    fun retry() {
        if (mode == PrivacyMode.OFF) return
        job?.cancel()
        job = scope.launch { connectLoop(first = null) }
    }

    /**
     * Called for SOCKS and connect failures only, never for HTTP status codes.
     * A few failures close together make the manager re-check the backend; one blip does nothing.
     */
    fun reportTransportFailure() {
        val connected = _state.value as? ConnectionState.Connected ?: return
        val time = now()
        synchronized(failures) {
            failures.addLast(time)
            while (failures.isNotEmpty() && time - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
            if (failures.size < FAILURES_TO_CHECK) return
            failures.clear()
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val backend = backends[connected.backend]
            if (backend != null && backend.verify(connected.socksPort)) return@launch
            _state.value = ConnectionState.Switching(connected.backend)
            // Try the other backend first, then the one that just failed.
            connectLoop(first = mode.candidates().firstOrNull { it != connected.backend })
        }
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        backends.values.forEach { runCatching { it.stop() } }
        _state.value = ConnectionState.Disabled
    }

    private suspend fun connectLoop(first: BackendId?) {
        val order = mode.candidates().sortedBy { if (it == first) 0 else 1 }
        var wait = FIRST_RETRY_MS
        repeat(MAX_ROUNDS) { round ->
            for (id in order) {
                val backend = backends[id] ?: continue
                if (_state.value !is ConnectionState.Switching) _state.value = ConnectionState.Connecting(id)
                val port = try {
                    backend.connect { percent -> _state.value = ConnectionState.Bootstrapping(id, percent.coerceIn(0, 100)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                if (port != null) {
                    backends.values.filter { it.id != id }.forEach { runCatching { it.stop() } }
                    _state.value = ConnectionState.Connected(id, port)
                    return
                }
            }
            if (round < MAX_ROUNDS - 1) {
                _state.value = ConnectionState.Unavailable("retrying")
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
        _state.value = ConnectionState.Unavailable("no working connection")
    }

    companion object {
        const val FAILURES_TO_CHECK = 3
        const val FAILURE_WINDOW_MS = 20_000L
        const val MAX_ROUNDS = 5
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
    }
}

/**
 * The process-wide proxy choice. OkHttp (and so Ktor, Coil and Media3's OkHttp data source) asks this for every
 * new connection. With SOCKS, OkHttp leaves the host name unresolved, so the name is resolved by Tor, not locally.
 */
class PrivacyProxySelector(
    private val protected: Boolean,
    private val socksPort: () -> Int?,
    private val onConnectFailed: () -> Unit = {},
    private val fallback: ProxySelector? = null,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        if (!protected) return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
        if (isLocal(uri?.host)) return listOf(Proxy.NO_PROXY)
        val port = socksPort() ?: return listOf(BLOCKED)
        return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, port)))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        if (protected && sa is InetSocketAddress && sa.address?.isLoopbackAddress == true) onConnectFailed()
        else if (!protected) fallback?.connectFailed(uri, sa, ioe)
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Nothing listens on the discard port, so a blocked request fails fast instead of going out directly. */
        val BLOCKED = Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, 9))

        private val PRIVATE_V4 = Regex("""^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)\d{1,3}(\.\d{1,3}){1,2}$""")

        /** Loopback and private-network IP literals (a WebDAV or SMB box at home) stay direct; every host name goes through Tor. */
        fun isLocal(host: String?): Boolean {
            val h = host?.trim('[', ']')?.lowercase() ?: return false
            return h == "localhost" || h == "::1" || PRIVATE_V4.matches(h)
        }
    }
}
