package com.bxane.nuvx.data.listentogether

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put




























class PartyClient(
    private val scope: CoroutineScope,
    private val client: HttpClient,

    private val localNowMs: () -> Long,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }








    suspend fun create(
        base: String,
        userId: String,
        deviceId: String,
        displayName: String,
        avatarUrl: String? = null,
        autoplayEnabled: Boolean? = null,
    ): PartyMembership = post(
        base = base,
        path = "/api/parties",
        body = buildJsonObject {
            put("userId", userId)
            put("deviceId", deviceId)
            put("displayName", displayName)
            avatarUrl?.let { put("avatarUrl", it) }
            autoplayEnabled?.let { put("autoplayEnabled", it) }
        },
        serializer = PartyMembership.serializer(),
    )






    suspend fun join(
        base: String,
        code: String,
        userId: String,
        deviceId: String,
        displayName: String,
        avatarUrl: String? = null,
    ): PartyMembership = post(
        base = base,
        path = "/api/parties/$code/join",
        body = buildJsonObject {
            put("userId", userId)
            put("deviceId", deviceId)
            put("displayName", displayName)
            avatarUrl?.let { put("avatarUrl", it) }
        },
        serializer = PartyMembership.serializer(),
    )










    suspend fun preview(base: String, code: String): PartyPreview = get(
        base = base,
        path = "/api/parties/$code/preview",
        serializer = PartyPreview.serializer(),
    )















    suspend fun leave(base: String, code: String, token: String) {
        val response = request {
            client.post(url(base, "/api/parties/$code/leave")) { bearerAuth(token) }
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            throw PartyException(response.status.value, errorCodeOf(text), errorMessageOf(text))
        }
    }









    suspend fun probe(base: String, timeoutMs: Long): ProbeResult {
        val normalized = ServerUrl.parseAndNormalize(base).normalizedOrNull.orEmpty()
        if (normalized.isEmpty()) return ProbeResult(isOnline = false)
        val started = localNowMs()
        return try {
            val response = withTimeout(timeoutMs) { client.get("$normalized/healthz") }
            val online = response.status.isSuccess() && response.bodyAsText().contains("\"ok\":true")
            val elapsed = (localNowMs() - started).coerceAtLeast(0)
            ProbeResult(isOnline = online, latencyMs = if (online) elapsed else 0)
        } catch (_: Throwable) {
            ProbeResult(isOnline = false)
        }
    }



    private suspend inline fun <reified T> post(
        base: String,
        path: String,
        body: JsonObject,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val response = request {
            client.post(url(base, path)) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        if (!response.status.isSuccess()) {
            throw PartyException(response.status.value, errorCodeOf(response.bodyAsText()), errorMessageOf(response.bodyAsText()))
        }
        return json.decodeFromString(serializer, response.bodyAsText())
    }

    private suspend inline fun <reified T> get(
        base: String,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val response = request { client.get(url(base, path)) }
        if (!response.status.isSuccess()) {
            throw PartyException(response.status.value, errorCodeOf(response.bodyAsText()), errorMessageOf(response.bodyAsText()))
        }
        return json.decodeFromString(serializer, response.bodyAsText())
    }









    private suspend inline fun request(
        crossinline call: suspend () -> HttpResponse,
    ): HttpResponse = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {



        throw PartyException(null, "transport", e.message ?: e.toString())
    }


    private fun url(base: String, path: String): String =
        ServerUrl.parseAndNormalize(base).normalizedOrNull.orEmpty() + path

    private fun errorCodeOf(text: String): String = runCatching {
        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    private fun errorMessageOf(text: String): String = runCatching {
        json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    companion object {






        const val PING_INTERVAL_MS = 5_000L
    }
}


class PartyException(
    val statusCode: Int?,
    val errorCode: String,
    override val message: String,
) : Exception(message) {









    fun isEligibleForFallback(): Boolean = when {
        statusCode == null -> true
        errorCode == "host_only" -> false
        errorCode == "party_full" -> false
        errorCode == "not_found" -> false
        else -> statusCode in 500..599
    }









    fun asFailure(): PartyFailure = when {
        statusCode == null -> PartyFailure.Transport(errorCode)
        errorCode in REFUSALS -> PartyFailure.Rejected(statusCode)
        else -> PartyFailure.Server(statusCode)
    }

    private companion object {

        val REFUSALS = setOf("host_only", "party_full", "not_found", "invalid", "forbidden", "rate_limited")
    }
}
