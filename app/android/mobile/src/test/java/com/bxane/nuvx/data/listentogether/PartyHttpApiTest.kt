package com.bxane.nuvx.data.listentogether

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyHttpApiTest {
    @Test
    fun healthProbeRequiresSuccessfulOkResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.enqueue(MockResponse().setBody("{\"ok\":false}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"ok\":true}"))
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val http = http(json)
        val api = PartyHttpApi(http, json)

        try {
            assertTrue(api.probeHealth(server.url("/").toString().trimEnd('/'), 1000L))
            assertFalse(api.probeHealth(server.url("/").toString().trimEnd('/'), 1000L))
            assertFalse(api.probeHealth(server.url("/").toString().trimEnd('/'), 1000L))
            assertEquals("/healthz", server.takeRequest().path)
            assertEquals("/healthz", server.takeRequest().path)
            assertEquals("/healthz", server.takeRequest().path)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun membershipRequestEncodesBodyAndDecodesResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
                """{"code":"ABC123","token":"t","you":{"memberId":"m","userId":"u","displayName":"n","isHost":true},"party":{"code":"ABC123","members":[]}}""",
            ),
        )
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val http = http(json)
        val api = PartyHttpApi(http, json)

        try {
            val membership = api.post(
                server.url("/api/parties").toString(),
                JoinRequest("u", "d", "n"),
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/parties", request.path)
            assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
            assertEquals("{\"userId\":\"u\",\"deviceId\":\"d\",\"displayName\":\"n\"}", request.body.readUtf8())
            assertEquals("ABC123", membership.code)
            assertEquals("t", membership.token)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun membershipRequestPreservesPartyErrorFields() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"party_full\",\"message\":\"Full\"}"))
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val http = http(json)
        val api = PartyHttpApi(http, json)

        try {
            val error = runCatching {
                api.post(server.url("/api/parties").toString(), JoinRequest("u", "d", "n"))
            }.exceptionOrNull() as ListenTogether.PartyException
            assertEquals("party_full", error.code)
            assertEquals("Full", error.message)
            assertEquals(409, error.statusCode)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun previewNotFoundReturnsAnEmptyPreview() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val http = http(json)
        val api = PartyHttpApi(http, json)

        try {
            assertEquals(PartyPreview(code = "ABC123"), api.preview(server.url("/").toString().trimEnd('/'), "ABC123"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun staleSlotReleaseIncludesBearerToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val http = http(json)
        val api = PartyHttpApi(http, json)

        try {
            api.releaseStaleSlot(server.url("/").toString().trimEnd('/'), "ABC123", "held")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/parties/ABC123/leave", request.path)
            assertEquals("Bearer held", request.getHeader("Authorization"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    private fun http(json: Json) = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout)
        expectSuccess = false
    }
}
