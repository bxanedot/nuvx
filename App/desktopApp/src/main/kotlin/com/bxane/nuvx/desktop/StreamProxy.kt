package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.AudioStream
import com.bxane.nuvx.core.VideoStream
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** VLC gets a private loopback URL; source credentials and signed addresses never enter native player options. */
internal class StreamProxy : AutoCloseable {
    private data class Source(val url: String, val headers: Map<String, String>)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "nuvx-media").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val active = ConcurrentHashMap<String, Source>()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val current = active[exchange.requestURI.path]
            if (current == null) {
                exchange.sendResponseHeaders(404, -1); exchange.close()
            } else if (exchange.requestMethod !in setOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(405, -1); exchange.close()
            } else {
                try {
                    val request = HttpRequest.newBuilder(URI(current.url)).timeout(Duration.ofSeconds(45))
                    current.headers.forEach { (key, value) -> request.setHeader(key, value) }
                    exchange.requestHeaders.getFirst("Range")?.let { request.setHeader("Range", it) }
                    val response = http.send(request.method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofInputStream())
                    response.body().use { body ->
                        for (key in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")) {
                            response.headers().firstValue(key).ifPresent { exchange.responseHeaders.set(key, it) }
                        }
                        val length = response.headers().firstValueAsLong("Content-Length").orElse(0)
                        exchange.sendResponseHeaders(response.statusCode(), if (exchange.requestMethod == "HEAD") -1 else length)
                        if (exchange.requestMethod != "HEAD") exchange.responseBody.use { body.copyTo(it) }
                    }
                } catch (_: Exception) {
                    // A decoder may close a range early while seeking. Never print credential-bearing request exceptions.
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                } finally { exchange.close() }
            }
        }
        server.start()
    }

    fun register(stream: AudioStream): String {
        clear()
        return register(stream.url, stream.headers, stream.mimeType)
    }

    fun registerVideo(stream: VideoStream): Pair<String, String> {
        clear()
        val picture = register(stream.videoUrl, stream.headers, stream.mimeType)
        val audio = register(stream.audioUrl, stream.headers, stream.audioMimeType)
        return picture to audio
    }

    private fun register(url: String, headers: Map<String, String>, mimeType: String?): String {
        val extension = when {
            mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
            mimeType?.contains("mp4", ignoreCase = true) == true -> "mp4"
            mimeType?.contains("mpeg", ignoreCase = true) == true -> "mp3"
            else -> "mp4"
        }
        val path = "/${UUID.randomUUID()}.$extension"
        active[path] = Source(url, headers)
        return "http://127.0.0.1:${server.address.port}$path"
    }
    fun clear() = active.clear()
    override fun close() { active.clear(); server.stop(0); executor.shutdownNow(); http.close() }
}
