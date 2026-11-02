package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class StreamProxyTest {
    @Test fun keepsHeadersAndRangesIsolatedPerTrack() {
        val received = mutableListOf<Pair<String?, String?>>()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            received += exchange.requestHeaders.getFirst("X-Track") to exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.set("Content-Range", "bytes 0-3/4")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write("data".toByteArray()) }
        }
        origin.start()
        try {
            StreamProxy().use { proxy ->
                fun stream(value: String) = AudioStream("dQw4w9WgXcQ", "http://127.0.0.1:${origin.address.port}/", mapOf("X-Track" to value), null, null, null, BackendId.INNERTUBEX)
                val client = HttpClient.newHttpClient()
                val first = proxy.register(stream("first"))
                val response = client.send(HttpRequest.newBuilder(URI(first)).header("Range", "bytes=0-3").build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(206, response.statusCode())
                assertEquals("data", response.body())
                assertEquals("bytes 0-3/4", response.headers().firstValue("Content-Range").orElse(null))
                val second = proxy.register(stream("second"))
                assertEquals(404, client.send(HttpRequest.newBuilder(URI(first)).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                assertEquals(206, client.send(HttpRequest.newBuilder(URI(second)).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                assertEquals(listOf<Pair<String?, String?>>("first" to "bytes=0-3", "second" to null), received)
                val unknown = URI(second).resolve("/unknown")
                assertEquals(404, client.send(HttpRequest.newBuilder(unknown).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            }
        } finally { origin.stop(0) }
    }
}
