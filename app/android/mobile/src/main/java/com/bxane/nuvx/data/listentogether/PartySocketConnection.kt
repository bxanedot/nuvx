package com.bxane.nuvx.data.listentogether

import com.bxane.nuvx.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PartySocketConnection(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val partyCode: () -> String?,
    private val token: () -> String?,
    private val state: () -> ListenTogether.State,
    private val updateState: ((ListenTogether.State) -> ListenTogether.State) -> Unit,
    private val clock: ServerClock,
    private val socketBase: () -> String,
    private val setSession: (DefaultClientWebSocketSession?) -> Unit,
    private val onFrame: (String) -> Unit,
    private val partyPositionMs: () -> Long?,
    private val redact: (String?) -> String,
    private val tag: String,
) {
    suspend fun run() {
        var backoffMs = 1_000L
        while (currentScopeActive()) {
            val code = partyCode() ?: return
            val held = token() ?: return
            try {
                updateState { it.copy(connection = ListenTogether.Connection.CONNECTING) }
                http.webSocket(
                    urlString = "${socketBase()}/ws/parties/$code",
                    request = { header("Authorization", "Bearer $held") },
                ) {
                    setSession(this)
                    backoffMs = 1_000L
                    updateState { it.copy(connection = ListenTogether.Connection.LIVE, error = null) }
                    launch { pingLoop() }
                    launch { reportLoop() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(tag, "party socket dropped: ${redact(failure.message)}")
            } finally {
                setSession(null)
            }
            if (!currentScopeActive()) return
            updateState { it.copy(connection = ListenTogether.Connection.CONNECTING) }
            clock.reset()
            updateState { it.copy(clockSynced = false) }
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun currentScopeActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun DefaultClientWebSocketSession.pingLoop() {
        repeat(4) {
            ping()
            delay(300)
        }
        while (true) {
            delay(PING_INTERVAL_MS)
            ping()
        }
    }

    private suspend fun DefaultClientWebSocketSession.ping() {
        val sentAt = clock.nowMs()
        val frame = buildJsonObject {
            put("type", "ping")
            put("clientMs", sentAt)
        }
        runCatching { send(Frame.Text(frame.toString())) }
    }

    private suspend fun DefaultClientWebSocketSession.reportLoop() {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            val position = partyPositionMs() ?: continue
            val frame = buildJsonObject {
                put("type", "report")
                put("positionMs", position)
                put("isPlaying", state().playback.isPlaying)
            }
            runCatching { send(Frame.Text(frame.toString())) }
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 15_000L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}
