package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
}
