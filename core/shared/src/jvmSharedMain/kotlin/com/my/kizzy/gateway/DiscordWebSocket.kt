package com.my.kizzy.gateway

import com.my.kizzy.gateway.entities.Heartbeat
import com.my.kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import com.my.kizzy.gateway.entities.Payload
import com.my.kizzy.gateway.entities.Ready
import com.my.kizzy.gateway.entities.Resume
import com.my.kizzy.gateway.entities.op.OpCode
import com.my.kizzy.gateway.entities.op.OpCode.DISPATCH
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT_ACK
import com.my.kizzy.gateway.entities.op.OpCode.HELLO
import com.my.kizzy.gateway.entities.op.OpCode.IDENTIFY
import com.my.kizzy.gateway.entities.op.OpCode.INVALID_SESSION
import com.my.kizzy.gateway.entities.op.OpCode.PRESENCE_UPDATE
import com.my.kizzy.gateway.entities.op.OpCode.RECONNECT
import com.my.kizzy.gateway.entities.op.OpCode.RESUME
import com.my.kizzy.gateway.entities.presence.Presence
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

open class DiscordWebSocket(
    private val token: String,
    private val os: String = "Android",
    private val browser: String = "Discord Android",
    private val device: String = "Generic Android Device",
) : CoroutineScope {
    private val logger = Logger.getLogger(DiscordWebSocket::class.java.name)
    private val gatewayUrl = "wss://gateway.discord.gg/?v=9&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val supervisor = SupervisorJob()

    override val coroutineContext: CoroutineContext = supervisor + Dispatchers.Default

    private var connectionLoop: Job? = null

    private val loopLock = Any()

    @Volatile
    private var closed = false

    private val sessionReady = MutableStateFlow(false)

    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastAckAt = 0L

    @Volatile
    private var awaitingAck = false

    @Volatile
    private var immediateRetry = false

    @Volatile
    private var lastPresence: Presence? = null

    @Volatile
    private var deliveredPresence: Presence? = null

    fun connect() {
        if (closed) return
        synchronized(loopLock) {
            if (connectionLoop?.isActive == true) {
                logger.info("Gateway connection loop already running.")
                return
            }
            connectionLoop = launch { runConnectionLoop() }
        }
    }

    fun retryNow() {
        if (closed) return
        retrySignal.trySend(Unit)
    }

    private suspend fun runConnectionLoop() {
        var backoff = INITIAL_RECONNECT_DELAY.inWholeMilliseconds
        while (currentCoroutineContext().isActive && !closed) {
            val url = resumeGatewayUrl ?: gatewayUrl
            var connected = false
            try {
                logger.info("Connecting to Discord Gateway at $url")
                val session = client.webSocketSession(url) {
                    header("User-Agent", "Discord-Android/314013;RNA")
                    header("Accept-Language", "en-US")
                    header("Cache-Control", "no-cache")
                    header("Pragma", "no-cache")
                }
                websocket = session
                connected = true
                backoff = INITIAL_RECONNECT_DELAY.inWholeMilliseconds
                logger.info("Successfully connected to Discord Gateway.")
                session.incoming.receiveAsFlow().collect { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        runCatching { onMessage(json.decodeFromString(text)) }
                            .onFailure { logger.warning("Gateway: bad payload: ${it.message}") }
                    }
                }
                val reason = session.closeReason.await()
                logger.warning(
                    "Gateway closed with code: ${reason?.code}, reason: ${reason?.message}",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                logger.warning("Gateway connection error: ${e.message}")
            } finally {
                teardownSocket()
            }

            if (closed) break

            if (!connected) forgetSession()

            if (immediateRetry) {
                immediateRetry = false
                continue
            }
            logger.info("Gateway: reconnecting in ${backoff}ms")
            withTimeoutOrNull(backoff) { retrySignal.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_DELAY.inWholeMilliseconds)
        }
    }

    private fun teardownSocket() {
        sessionReady.value = false
        awaitingAck = false
        deliveredPresence = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        websocket?.cancel()
        websocket = null
    }

    private fun forgetSession() {
        resumeGatewayUrl = null
        sessionId = null
        sequence = 0
    }

    private fun dropSocket(why: String) {
        logger.warning("Gateway: dropping socket ($why)")
        sessionReady.value = false
        websocket?.cancel()
    }

    private suspend fun onMessage(payload: Payload) {
        logger.info("Gateway received: op=${payload.op}, seq=${payload.s}, event=${payload.t}")
        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch()
            HEARTBEAT -> sendHeartBeat()
            HEARTBEAT_ACK -> {
                awaitingAck = false
                lastAckAt = elapsedRealtimeMs()
            }
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> payload.handleHello()
            else -> {}
        }
    }

    open fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(this.d!!)
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=9&encoding=json"
                logger.info(
                    "Gateway READY: resume_gateway_url updated to $resumeGatewayUrl, " +
                        "session_id updated to $sessionId",
                )
                onSessionReady()
                return
            }

            "RESUMED" -> {
                logger.info("Gateway: Session Resumed")
                onSessionReady()
            }

            else -> {}
        }
    }

    private fun onSessionReady() {
        lastAckAt = elapsedRealtimeMs()
        sessionReady.value = true
        val presence = lastPresence ?: return
        launch {
            logger.info("Gateway: replaying presence onto the new session")
            runCatching { sendPresence(presence) }
        }
    }

    private suspend fun handleInvalidSession() {
        logger.warning("Gateway: Handling Invalid Session. Sending Identify after 150ms")
        forgetSession()
        delay(150)
        sendIdentify()
    }

    private suspend fun Payload.handleHello() {
        heartbeatInterval = json.decodeFromJsonElement<Heartbeat>(this.d!!).heartbeatInterval
        logger.info("Gateway: Setting heartbeatInterval=$heartbeatInterval")
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        startHeartbeatJob(heartbeatInterval)
    }

    private suspend fun sendHeartBeat() {
        logger.info("Gateway: Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private fun reconnectWebSocket() {
        immediateRetry = true
        dropSocket("gateway asked us to reconnect")
    }

    private suspend fun sendIdentify() {
        logger.info("Gateway: Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload(
                os = os,
                browser = browser,
                device = device,
            ),
        )
    }

    private suspend fun sendResume() {
        logger.info("Gateway: Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token,
            ),
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        awaitingAck = false
        lastAckAt = elapsedRealtimeMs()
        heartbeatJob = launch {
            while (isActive) {
                if (awaitingAck) {
                    dropSocket("heartbeat went unacknowledged")
                    return@launch
                }
                awaitingAck = true
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean =
        sessionReady.value && websocket?.isActive == true && !isStale()

    private fun isStale(): Boolean {
        val interval = heartbeatInterval
        if (interval <= 0L || lastAckAt == 0L) return false
        val sinceAckMs = elapsedRealtimeMs() - lastAckAt
        return sinceAckMs > interval + STALE_GRACE.inWholeMilliseconds
    }

    fun isWebSocketConnected(): Boolean = isSocketConnectedToAccount()

    private suspend inline fun <reified T> send(op: OpCode, d: T?): Boolean {
        val socket = websocket?.takeIf { it.isActive } ?: return false
        val payload = json.encodeToString(
            Payload(
                op = op,
                d = json.encodeToJsonElement(d),
            ),
        )
        if (op == IDENTIFY) {
            logger.info("Gateway sending payload: [REDACTED IDENTIFY PAYLOAD]")
        } else {
            logger.info("Gateway sending payload: $payload")
        }
        socket.send(Frame.Text(payload))
        return true
    }

    fun close() {
        closed = true
        sessionReady.value = false
        lastPresence = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        connectionLoop?.cancel()
        connectionLoop = null
        forgetSession()
        val socket = websocket
        websocket = null
        runBlocking {
            withTimeoutOrNull(CLOSE_TIMEOUT) {
                runCatching { socket?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closed")) }
            }
        }
        socket?.cancel()
        supervisor.cancel()
        runCatching { client.close() }
        logger.info("Gateway: Connection to gateway closed")
    }

    suspend fun sendActivity(presence: Presence) {
        lastPresence = presence
        if (closed) return
        if (sessionReady.value && isStale()) dropSocket("stale before a presence push")
        connect()
        retryNow()
        val ready = withTimeoutOrNull(SESSION_WAIT_TIMEOUT.inWholeMilliseconds) {
            sessionReady.first { it }
        }
        if (ready == null) {
            logger.warning("Gateway: no session for presence push; it will be replayed on reconnect")
            return
        }
        sendPresence(presence)
    }

    suspend fun clearActivity() {
        val presence = Presence(activities = emptyList())
        lastPresence = presence
        if (closed || !isSocketConnectedToAccount()) return
        sendPresence(presence)
    }

    private suspend fun sendPresence(presence: Presence) {
        if (deliveredPresence == presence) {
            logger.info("Gateway: presence unchanged on this session; not resending")
            return
        }
        logger.info("Gateway: Sending $PRESENCE_UPDATE")
        if (send(op = PRESENCE_UPDATE, d = presence)) {
            deliveredPresence = presence
        }
    }

    companion object {
        private val INITIAL_RECONNECT_DELAY = 1.seconds
        private val MAX_RECONNECT_DELAY = 60.seconds

        private val STALE_GRACE = 15.seconds

        private val SESSION_WAIT_TIMEOUT = 20.seconds

        private val CLOSE_TIMEOUT = 2.seconds
    }
}

private val androidElapsedRealtime: java.lang.reflect.Method? = runCatching {
    Class.forName("android.os.SystemClock").getMethod("elapsedRealtime")
}.getOrNull()

internal fun elapsedRealtimeMs(): Long =
    androidElapsedRealtime?.let { runCatching { it.invoke(null) as Long }.getOrNull() }
        ?: (System.nanoTime() / 1_000_000L)
