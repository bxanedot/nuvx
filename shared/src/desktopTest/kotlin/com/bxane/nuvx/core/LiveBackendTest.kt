package com.bxane.nuvx.core

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Hits YouTube Music for real. Opt-in: NUVX_LIVE=1 ./gradlew :shared:desktopTest --tests '*LiveBackendTest*' */
class LiveBackendTest {
    private val enabled = System.getenv("NUVX_LIVE") == "1"

    @Test fun innerTubeX() = check(InnerTubeXBackend())

    @Test fun excor() = check(ExcorBackend())

    private fun check(backend: MusicBackend) = runBlocking {
        if (!enabled) return@runBlocking
        val results = backend.search("daft punk around the world")
        println("${backend.id}: ${results.tracks.size} tracks, ${results.collections.size} collections; first=${results.tracks.firstOrNull()}")
        assertTrue(results.tracks.isNotEmpty(), "${backend.id} search returned no tracks")
        val seed = results.tracks.first().videoId
        val radio = backend.radio(seed)
        println("${backend.id}: radio ${radio.tracks.size} tracks, continuation=${radio.continuation != null}")
        assertTrue(radio.tracks.size > 5, "${backend.id} radio too short")
        // eXcor mints no PO tokens, so YouTube currently refuses its streams; see docs/BLOCKERS.md.
        if (backend.id == BackendId.EXCOR) {
            println("EXCOR stream: " + runCatching { backend.stream(seed) }.fold({ it?.toString() ?: "null" }, { "refused: ${it.message}" }))
            return@runBlocking
        }
        val stream = assertNotNull(backend.stream(seed), "${backend.id} returned no stream")
        println("${backend.id}: $stream")
        // Prove the URL actually serves audio bytes with the headers the engine says it needs.
        val bytes = ByteArray(4096)
        val read = nuvxHttpClient().use { http ->
            http.get(stream.url) { stream.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-4095") }
                .bodyAsChannel().readAvailable(bytes)
        }
        println("${backend.id}: fetched $read audio bytes")
        assertTrue(read > 0)
    }
}
