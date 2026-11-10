package com.bxane.nuvx.data.presence

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class PresencePlatform(val wire: String) { Android("android"), Pc("pc") }

class Presence(
    private val platform: PresencePlatform,
    private val installId: String,
    private val endpoint: String = ENDPOINT,
) {
    private val open = MutableStateFlow(false)
    @Volatile private var announced = false
    @Volatile private var intervalSec = DEFAULT_INTERVAL_SEC

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            open.collectLatest { isOpen ->
                if (isOpen) {
                    while (true) {
                        send(open = true)
                        delay(nextDelayMs())
                    }
                } else if (announced) {
                    delay(CLOSE_DEBOUNCE_MS)
                    send(open = false)
                }
            }
        }
    }

    fun setOpen(isOpen: Boolean) {
        open.value = isOpen
    }

    fun closeBlocking() {
        open.value = false
        if (announced) send(open = false, timeoutSec = 2)
    }

    private fun nextDelayMs(): Long {
        val base = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC) * 1000L
        return base + Random.nextLong(-base / 10, base / 10 + 1)
    }

    private fun send(open: Boolean, timeoutSec: Long = 15) {
        val body = buildJsonObject {
            put("id", installId)
            put("platform", platform.wire)
            put("open", open)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(endpoint).post(body).build()
        if (open) announced = true
        try {
            val call = Http.client.newCall(request)
            call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!open) announced = false
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "presence ping refused: HTTP ${response.code}")
                    return
                }
                val text = response.body?.string().orEmpty()
                runCatching { Json.parseToJsonElement(text).jsonObject["intervalSec"]?.jsonPrimitive?.intOrNull }
                    .getOrNull()
                    ?.let { intervalSec = it }
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "presence ping failed: ${e.message}")
        }
    }

    companion object {
        const val ENDPOINT = "https://nuvx-listen.bxane.workers.dev/api/presence"
        private const val TAG = "Presence"
        private const val DEFAULT_INTERVAL_SEC = 300
        private const val MIN_INTERVAL_SEC = 60
        private const val MAX_INTERVAL_SEC = 3600
        private const val CLOSE_DEBOUNCE_MS = 2_000L
        private val JSON = "application/json".toMediaType()
    }
}
