package com.bxane.nuvx.core.lyrics

import com.bxane.nuvx.core.Lrc
import com.bxane.nuvx.core.Lyrics
import com.bxane.nuvx.core.Track
import com.bxane.nuvx.core.defaultLyricsProviders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LyricsProvidersTest {
    private val track = Track("abcdefghijk", "Silver Comet", listOf("bxane"), "Night Drive", 123_000)

    @Test fun onlyDocumentedProvidersAreRegistered() {
        val http = HttpClient(MockEngine { error("Provider registration must not send requests") })
        try {
            assertEquals(listOf("Unison", "LRCLIB", "lrc.red", "Better Lyrics"), defaultLyricsProviders(http).map { it.name })
        } finally {
            http.close()
        }
    }

    @Test fun lrcRedUsesItsSearchAndIsrcTtmlApiWithRequiredClientIdentity() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/search.json" -> respond(
                    """{"hits":[{"isrc":"GBABX0000001","title":"Silver Comet","artist":"bxane"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/s/GBABX0000001.ttml" -> respond(
                    """<tt><body><p begin="00:00:01.000"><span begin="00:00:01.000">silver comet</span></p></body></tt>""",
                )
                else -> error("Unexpected lyrics request")
            }
        })

        try {
            val provider = LrcRed(http)
            val result = assertIs<Lyrics.Synced>(provider.find(track))
            assertEquals("silver comet", result.lines.single().text)
            assertEquals(listOf("/search.json", "/s/GBABX0000001.ttml"), requests.map { it.url.encodedPath })
            assertEquals("Silver Comet bxane", requests.first().url.parameters["q"])
            assertTrue(requests.all { it.headers[HttpHeaders.UserAgent]!!.contains("bxanee@gmail.com") })
            assertEquals("https://lrc.red", provider.homepage)
        } finally {
            http.close()
        }
    }

    @Test fun betterLyricsUsesRequiredMatchFieldsAndReturnsOnlySuppliedTiming() = runTest {
        lateinit var request: io.ktor.client.request.HttpRequestData
        val http = HttpClient(MockEngine { data ->
            request = data
            respond(
                """{"ttml":"<tt><body><p begin=\"00:00:02.000\"><span begin=\"00:00:02.000\">fictional word</span></p></body></tt>"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        try {
            val provider = BetterLyrics(http)
            val result = assertIs<Lyrics.Synced>(provider.find(track))
            assertEquals(2_000L, result.lines.single().words.single().startMs)
            assertEquals("Silver Comet", request.url.parameters["s"])
            assertEquals("bxane", request.url.parameters["a"])
            assertEquals("Night Drive", request.url.parameters["al"])
            assertEquals("123", request.url.parameters["d"])
            assertEquals("https://better-lyrics.boidu.dev", provider.homepage)
        } finally {
            http.close()
        }
    }

    @Test fun unisonSupportsDocumentedFormatsAndNeverSendsLocalIds() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(
                """{"success":true,"data":{"format":"lrc","lyrics":"[00:01.00]fictional phrase"}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        try {
            val provider = Unison(http)
            assertIs<Lyrics.Synced>(provider.find(track))
            assertEquals(track.videoId, requests.single().url.parameters["v"])
            assertNull(provider.find(track.copy(videoId = "local:track-1")))
            assertEquals(1, requests.size)
            assertIs<Lyrics.Synced>(Lrc.parse("[00:01.00]fictional phrase"))
        } finally {
            http.close()
        }
    }

    @Test fun rateLimitedResponsesAreNotRetried() = runTest {
        var count = 0
        val http = HttpClient(MockEngine {
            count++
            respond("Too Many Requests", status = HttpStatusCode.TooManyRequests)
        })

        try {
            assertNull(LrcRed(http).find(track))
            assertEquals(1, count)
        } finally {
            http.close()
        }
    }
}
