package com.licht.nuvx.data.sources

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RadioJavanTransportTest {
    @Test
    fun catalogueAndCappedMediaUseTheProviderProtocol() = runTest {
        val calls = mutableListOf<String>()
        val row = """{"id":"fixture","song":"Example","artist":"Artist","album":"Album","photo":"https://img.example/cover.jpg","duration":"180","hq_link":"https://media.example/aac-256/song.m4a?token=x","lq_link":"https://media.example/aac-128/song.m4a?token=y"}"""
        val source = RadioJavanSource(SourceConfig(kind = SourceKind.RADIOJAVAN)) { path, args ->
            calls += path
            when (path) {
                "search" -> { assertEquals("Example", args["query"]); """{"mp3s":[$row]}""" }
                "mp3" -> { assertEquals("fixture", args["id"]); """{"mp3":$row}""" }
                else -> error("Unexpected endpoint")
            }
        }

        val song = source.search("Example", 5, false, null).single()
        assertEquals("Example", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("3:00", song.durationText)
        assertEquals(SourceRegistry.trackKey(source.config.id, "fixture"), song.videoId)

        val stream = assertNotNull(source.stream("fixture", StreamRequest.Capped(128)))
        assertEquals("https://media.example/aac-128/song.m4a?token=y", stream.url)
        assertEquals(128, stream.format.kbps)
        assertEquals("aac", stream.format.codec)
        assertEquals(180, stream.durationSec)
        assertEquals(listOf("search", "mp3"), calls)
    }

    @Test
    fun rejectsNonHttpsArtworkAndLosslessRequests() = runTest {
        val row = """{"id":"fixture","song":"Example","photo":"http://img.example/cover.jpg","hq_link":"https://media.example/aac-256/song.m4a"}"""
        val source = RadioJavanSource(SourceConfig(kind = SourceKind.RADIOJAVAN)) { _, _ -> """{"mp3s":[$row]}""" }

        assertNull(source.search("Example", 1, false, null).single().thumbnailUrl)
        assertNull(source.stream("fixture", StreamRequest.Lossless))
        assertFalse(SourceKind.RADIOJAVAN.canServeLossless)
    }
}
