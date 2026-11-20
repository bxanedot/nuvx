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
    /** This device now belongs to [spaceId]. Store [token] in secure storage; it is shown once. */
    data class Joined(val spaceId: String, val deviceId: String, val token: String) : SyncResult
    /** Show [code] as a QR on this device and keep [secret] private; poll [SyncClient.claim] until [expiresAtMs]. */
    data class PairingStarted(val code: String, val secret: String, val expiresAtMs: Long) : SyncResult
    /** The code is not approved yet; poll again shortly. */
    data object Waiting : SyncResult
    /** The code was approved for a device named [deviceName]; it joins on its next claim. */
    data class Approved(val deviceName: String) : SyncResult
    data class Devices(val devices: List<SyncDevice>, val you: String) : SyncResult
    data object Done : SyncResult
}

data class SyncDevice(val id: String, val name: String, val createdMs: Long, val seenMs: Long)

/** Bounded exponential backoff: 5 s, 10 s, 20 s … capped at 30 minutes. */
fun retryDelayMs(attempt: Int): Long = (5_000L shl attempt.coerceIn(0, 20)).coerceAtMost(30 * 60_000L)

/**
 * Talks to a Nuvx Sync server (`core/sync`, API v1, see `SYNC_AND_CONNECT.md`). Blocking: call it off the main thread.
 * It keeps no state; the caller owns the outbox and the pull cursor, so a crash between calls loses nothing.
 */
class SyncClient(
    private val baseUrl: String,
    /** Null before this device has joined a space (creating one or claiming a pairing). */
    private val token: String?,
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

    /** Starts a new space with this device as its first member. */
    fun createSpace(deviceName: String): SyncResult =
        call(post("/v1/spaces", buildJsonObject { put("deviceName", deviceName) })) { joined(it) }

    /** On the new device: ask for a pairing code to show as a QR. */
    fun startPairing(deviceName: String): SyncResult =
        call(post("/v1/pairings", buildJsonObject { put("deviceName", deviceName) })) {
            SyncResult.PairingStarted(it.str("code"), it.str("secret"), it.getValue("expiresAtMs").jsonPrimitive.long)
        }

    /** On a trusted device, after the user confirmed the new device's name. */
    fun approve(code: String): SyncResult =
        call(post("/v1/pairings/${code.uppercase()}/approve", buildJsonObject {})) { SyncResult.Approved(it.str("deviceName")) }

    /** On the new device: [SyncResult.Waiting] until approved, then [SyncResult.Joined] once. */
    fun claim(code: String, secret: String): SyncResult =
        call(post("/v1/pairings/$code/claim", buildJsonObject { put("secret", secret) })) {
            if (it.containsKey("pending")) SyncResult.Waiting else joined(it)
        }

    fun devices(): SyncResult = call(Request.Builder().url("$baseUrl/v1/devices").get()) { json ->
        SyncResult.Devices(
            json.getValue("devices").jsonArray.map { it.jsonObject }.map {
                SyncDevice(it.str("id"), it.str("name"), it.getValue("createdMs").jsonPrimitive.long, it.getValue("seenMs").jsonPrimitive.long)
            },
            json.str("you"),
        )
    }

    /** Removes a device from the space; its token stops working at once. */
    fun revoke(deviceId: String): SyncResult = call(Request.Builder().url("$baseUrl/v1/devices/$deviceId").delete()) { SyncResult.Done }

    /** Revokes all other devices while keeping this caller's session and local data intact. */
    fun revokeOthers(): SyncResult =
        call(post("/v1/devices/revoke-others", buildJsonObject { put("confirm", true) })) { SyncResult.Done }

    /** Deletes the whole space on the server: every device, every op. Local data stays. */
    fun deleteSpace(): SyncResult = call(Request.Builder().url("$baseUrl/v1/space").delete()) { SyncResult.Done }

    private fun post(path: String, body: JsonObject) = Request.Builder().url("$baseUrl$path").post(body.toString().toRequestBody(JSON))
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun joined(json: JsonObject) = SyncResult.Joined(json.str("spaceId"), json.str("deviceId"), json.str("token"))

    private inline fun call(request: Request.Builder, ok: (JsonObject) -> SyncResult): SyncResult {
        token?.let { request.header("Authorization", "Bearer $it") }
        val response = try {
            client.newCall(request.build()).execute()
        } catch (e: IOException) {
            return SyncResult.RetryLater("Network: ${e.javaClass.simpleName}")
        }
        response.use {
            val code = it.code
            val error = runCatching { Json.parseToJsonElement(it.body.string()).jsonObject }.getOrNull()
            return when {
                code in 200..202 && error != null -> runCatching { ok(error) }.getOrElse { SyncResult.RetryLater("Unreadable reply") }
                code == 401 -> SyncResult.Stop(if (token == null) error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unauthorized" else "This device was removed from sync.")
                code == 404 || code == 409 || code == 413 || code == 422 -> SyncResult.Stop(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $code")
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
