package com.bxane.nuvx.data.listentogether

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

internal class PartyHttpApi(
    private val http: HttpClient,
    private val json: Json,
) {
    suspend fun probeHealth(serverBase: String, timeoutMs: Long): Boolean {
        val response = http.get("$serverBase/healthz") {
            timeout { requestTimeoutMillis = timeoutMs }
        }
        if (!response.status.isSuccess()) return false
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return body.contains("\"ok\":true")
    }

    suspend fun releaseStaleSlot(serverBase: String, code: String, held: String) {
        http.post("$serverBase/api/parties/$code/leave") {
            header("Authorization", "Bearer $held")
        }
    }

    suspend fun preview(serverBase: String, code: String): PartyPreview {
        val response = http.get("$serverBase/api/parties/$code/preview")
        if (!response.status.isSuccess()) {
            val problem = response.toPartyException()
            if (problem.statusCode == 404 && problem.code == "http_404") {
                return PartyPreview(code = code)
            }
            throw problem
        }
        return response.body()
    }

    suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    private suspend fun HttpResponse.toPartyException(): ListenTogether.PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return ListenTogether.PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: if (status.value == 422) "This account can't be used to jam."
                else "The party server said ${status.value}.",
            statusCode = status.value,
        )
    }
}
