package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityCanvasTest {
    private val asked = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private val canvas = CommunityCanvas(HttpClient(MockEngine { request ->
        asked += request.url.toString()
        respond("", status)
    }), folder = "https://example.test/canvas/")

    @Test fun findsByExactVideoIdAndRemembersTheAnswer() = runTest {
        assertEquals("https://example.test/canvas/abcdefghijk.mp4", canvas.find("abcdefghijk"))
        status = HttpStatusCode.NotFound
        assertNull(canvas.find("ABCDEFGHIJK"))
        canvas.find("abcdefghijk"); canvas.find("ABCDEFGHIJK")
        assertEquals(2, asked.size, "found and not-found answers are cached")
    }

    @Test fun errorsAreNotCachedAndBadIdsAreNeverRequested() = runTest {
        status = HttpStatusCode.InternalServerError
        assertNull(canvas.find("abcdefghijk"))
        status = HttpStatusCode.OK
        assertEquals("https://example.test/canvas/abcdefghijk.mp4", canvas.find("abcdefghijk"))
        assertNull(canvas.find("../../etc/x"))
        assertNull(canvas.find("short"))
        assertEquals(2, asked.size)
    }

    @Test fun missingCanvasIsRetriedAfterTenMinutes() = runTest {
        var now = 0L
        var available = false
        var requests = 0
        val retrying = CommunityCanvas(HttpClient(MockEngine {
            requests++
            respond("", if (available) HttpStatusCode.OK else HttpStatusCode.NotFound)
        }), folder = "https://example.test/canvas/", nowMillis = { now })

        assertNull(retrying.find("abcdefghijk"))
        assertNull(retrying.find("abcdefghijk"))
        assertEquals(1, requests, "a recent miss is cached")

        now += 10 * 60 * 1000L
        available = true
        assertEquals("https://example.test/canvas/abcdefghijk.mp4", retrying.find("abcdefghijk"))
        assertEquals(2, requests, "the expired miss is checked again")
    }

    @Test fun findsSharedCanvasByNormalizedTitleAndPrimaryArtist() = runTest {
        val requests = mutableListOf<String>()
        val shared = CommunityCanvas(HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            when {
                request.url.toString().endsWith("/index.json") -> respond("""{"falling down|lil peep":"abcdefghijk"}""", headers = headersOf("Content-Type", "application/json"))
                request.url.toString().endsWith("/abcdefghijk.mp4") -> respond("", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }), folder = "https://example.test/canvas/")
        assertEquals("https://example.test/canvas/abcdefghijk.mp4", shared.find(Track("ABCDEFGHIJK", " Falling   Down ", listOf("Lil Peep - Topic"))))
        assertEquals(3, requests.size, "exact id, index, then shared video")
    }

    @Test fun failedIndexFetchCanRetryAndInvalidSharedIdsAreIgnored() = runTest {
        var indexStatus = HttpStatusCode.InternalServerError
        val retrying = CommunityCanvas(HttpClient(MockEngine { request ->
            if (request.url.toString().endsWith("/index.json")) respond(if (indexStatus == HttpStatusCode.OK) """{"song|artist":"../../bad"}""" else "", indexStatus, headersOf("Content-Type", "application/json"))
            else respond("", HttpStatusCode.NotFound)
        }), folder = "https://example.test/canvas/")
        val track = Track("ABCDEFGHIJK", "Song", listOf("Artist"))
        assertNull(retrying.find(track))
        indexStatus = HttpStatusCode.OK
        assertNull(retrying.find(track))
    }

    @Test fun refreshesSharedIndexAfterTenMinutes() = runTest {
        var now = 0L
        var uploaded = false
        var indexRequests = 0
        val refreshing = CommunityCanvas(HttpClient(MockEngine { request ->
            when {
                request.url.toString().endsWith("/index.json") -> {
                    indexRequests++
                    respond(if (uploaded) """{"song|artist":"abcdefghij2"}""" else "{}", headers = headersOf("Content-Type", "application/json"))
                }
                request.url.toString().endsWith("/abcdefghij2.mp4") -> respond("", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }), folder = "https://example.test/canvas/", nowMillis = { now })
        val track = Track("ABCDEFGHIJK", "Song", listOf("Artist"))

        assertNull(refreshing.find(track))
        assertNull(refreshing.find(track))
        assertEquals(1, indexRequests, "a fresh shared index is reused")

        now += 10 * 60 * 1000L
        uploaded = true
        assertEquals("https://example.test/canvas/abcdefghij2.mp4", refreshing.find(track))
        assertEquals(2, indexRequests, "an expired shared index is refreshed")
    }

    @Test fun keepsUsingLastSharedIndexWhenRefreshFails() = runTest {
        var now = 0L
        var indexStatus = HttpStatusCode.OK
        var indexRequests = 0
        val resilient = CommunityCanvas(HttpClient(MockEngine { request ->
            when {
                request.url.toString().endsWith("/index.json") -> {
                    indexRequests++
                    respond(if (indexStatus == HttpStatusCode.OK) """{"song|artist":"abcdefghij2"}""" else "", indexStatus, headersOf("Content-Type", "application/json"))
                }
                request.url.toString().endsWith("/abcdefghij2.mp4") -> respond("", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }), folder = "https://example.test/canvas/", nowMillis = { now })
        val track = Track("ABCDEFGHIJK", "Song", listOf("Artist"))

        val original = "https://example.test/canvas/abcdefghij2.mp4"
        assertEquals(original, resilient.find(track))
        now += 10 * 60 * 1000L
        indexStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(original, resilient.find(track))
        assertEquals(original, resilient.find(track))
        assertEquals(2, indexRequests, "a failed refresh is not retried on each lookup")
    }

    @Test fun sharedCanvasDoesNotReuseFallbackForExplicitRemixOrLiveTitles() = runTest {
        val mediaRequests = mutableListOf<String>()
        val shared = CommunityCanvas(HttpClient(MockEngine { request ->
            when {
                request.url.toString().endsWith("/index.json") -> respond(
                    """{"falling down (travis barker remix)|lil peep":"abcdefghij1","falling down (live)|lil peep":"abcdefghij2"}""",
                    headers = headersOf("Content-Type", "application/json"),
                )
                else -> {
                    val url = request.url.toString()
                    mediaRequests += url
                    respond("", if (url.endsWith("/abcdefghij1.mp4") || url.endsWith("/abcdefghij2.mp4")) HttpStatusCode.OK else HttpStatusCode.NotFound)
                }
            }
        }), folder = "https://example.test/canvas/")
        assertNull(shared.find(Track("ABCDEFGHIJK", "Falling Down (Travis Barker Remix)", listOf("Lil Peep"))))
        assertNull(shared.find(Track("ABCDEFGHIJ2", "Falling Down (Live)", listOf("Lil Peep"))))
        assertEquals(
            false,
            mediaRequests.any { it.endsWith("/abcdefghij1.mp4") || it.endsWith("/abcdefghij2.mp4") },
            "versioned tracks must not reuse a shared fallback",
        )
    }
}
