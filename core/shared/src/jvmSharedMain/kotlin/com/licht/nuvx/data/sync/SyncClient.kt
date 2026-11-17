package com.licht.nuvx.data.sync

import com.licht.nuvx.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A local change waiting to be sent. Keep [id] across retries: the server stores an id once, so a resend is harmless. */
data class PendingOp(val id: String, val kind: String, val entity: String, val payload: JsonObject, val clientMs: Long)

sealed interface SyncResult {
    /** These ids are stored on the server; remove them from the outbox. Others in the outbox go in the next call. */
    data class Pushed(val ids: List<String>) : SyncResult
    /** Apply [ops] with [replay], save [cursor], and pull again while [more]. */
    data class Pulled(val ops: List<SyncOp>, val cursor: Long, val more: Boolean) : SyncResult
    /** Nothing was lost; try again after [retryDelayMs] for this attempt. */
    data class RetryLater(val why: String) : SyncResult
    /** Retrying will not help (device revoked, space full, newer server, invalid op). Keep the outbox and tell the user. */
    data class Stop(val why: String) : SyncResult
}

/** Bounded exponential backoff: 5 s, 10 s, 20 s … capped at 30 minutes. */
fun retryDelayMs(attempt: Int): Long = (5_000L shl attempt.coerceIn(0, 20)).coerceAtMost(30 * 60_000L)

/**
 * Talks to a Nuvx Sync server (`core/sync`, API v1, see `SYNC_AND_CONNECT.md`). Blocking: call it off the main thread.
 * It keeps no state; the caller owns the outbox and the pull cursor, so a crash between calls loses nothing.
 */
class SyncClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = Http.client,
) {
    /** Sends the oldest ops that fit in one request, in order. */
    fun push(pending: List<PendingOp>): SyncResult {
        if (pending.isEmpty()) return SyncResult.Pushed(emptyList())
        val batch = ArrayList<JsonObject>()
        var bytes = 0
        for (op in pending) {
            val wire = buildJsonObject {
                put("id", op.id); put("kind", op.kind); put("entity", op.entity)
                put("payload", op.payload); put("clientMs", op.clientMs)
            }
            val size = wire.toString().toByteArray().size
            if (op.payload.toString().length > MAX_OP_BYTES) {
                if (batch.isEmpty()) return SyncResult.Stop("A change is too large to sync (${op.kind}).")
                break // send what is ahead of it first
            }
            if (batch.size == MAX_OPS_PER_PUSH || bytes + size > MAX_BODY_BYTES) break
            batch += wire
            bytes += size
        }
        val body = buildJsonObject { put("ops", buildJsonArray { batch.forEach { add(it) } }) }.toString()
        return call(Request.Builder().url("$baseUrl/v1/ops").post(body.toRequestBody(JSON))) {
            SyncResult.Pushed(batch.map { it.getValue("id").jsonPrimitive.content })
        }
    }

    /** One page of the server's log after [cursor] (0 for everything). */
    fun pull(cursor: Long): SyncResult = call(Request.Builder().url("$baseUrl/v1/ops?after=$cursor").get()) { json ->
        val api = json["api"]?.jsonPrimitive?.intOrNull ?: 0
        if (api > API_VERSION) return@call SyncResult.Stop("This sync server is newer than the app. Update nuvx to keep syncing.")
        val ops = json.getValue("ops").jsonArray.map { it.jsonObject }.map {
            SyncOp(
                seq = it.getValue("seq").jsonPrimitive.long,
                kind = it.getValue("kind").jsonPrimitive.content,
                entity = it.getValue("entity").jsonPrimitive.content,
                payload = it["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        SyncResult.Pulled(ops, ops.lastOrNull()?.seq ?: cursor, json["more"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private inline fun call(request: Request.Builder, ok: (JsonObject) -> SyncResult): SyncResult {
        val response = try {
            client.newCall(request.header("Authorization", "Bearer $token").build()).execute()
        } catch (e: IOException) {
            return SyncResult.RetryLater("Network: ${e.javaClass.simpleName}")
        }
        response.use {
            val code = it.code
            val error = runCatching { Json.parseToJsonElement(it.body.string()).jsonObject }.getOrNull()
            return when {
                code == 200 && error != null -> runCatching { ok(error) }.getOrElse { SyncResult.RetryLater("Unreadable reply") }
                code == 401 -> SyncResult.Stop("This device was removed from sync.")
                code == 413 || code == 422 -> SyncResult.Stop(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $code")
                else -> SyncResult.RetryLater("HTTP $code") // 429, 507 server_full, 5xx, proxies
            }
        }
    }

    companion object {
        const val API_VERSION = 1
        // Server limits from core/sync/src/server.ts, with headroom for the request envelope.
        const val MAX_OPS_PER_PUSH = 200
        const val MAX_OP_BYTES = 32 * 1024
        const val MAX_BODY_BYTES = 240 * 1024
        private val JSON = "application/json".toMediaType()
    }
}
