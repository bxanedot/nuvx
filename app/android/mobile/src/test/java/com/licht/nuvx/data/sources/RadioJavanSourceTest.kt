package com.licht.nuvx.data.sources

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull

class RadioJavanSourceTest {
    @Test
    fun searchAndCappedStreamUseTheRadioJavanCatalogue() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val row = """{"id":"fixture","song":"Example","artist":"Artist","album":"Album","photo":"https://img.example/cover.jpg","duration":"180","hq_link":"https://media.example/aac-256/song.m4a?token=x","lq_link":"https://media.example/aac-128/song.m4a?token=y"}"""
        val source = RadioJavanSource(SourceConfig(kind = SourceKind.RADIOJAVAN)) { path, query ->
            calls += path to query
            when (path) {
                "search" -> { assertEquals("Example", query["query"]); """{"mp3s":[$row]}""" }
                "mp3" -> { assertEquals("fixture", query["id"]); """{"mp3":$row}""" }
                else -> error("Unexpected endpoint")
            }
        }

        val song = source.search(" Example ", 5, false, null).single()
        assertEquals("Example", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("3:00", song.durationText)
        assertEquals("https://img.example/cover.jpg", song.thumbnailUrl)
        assertEquals(SourceRegistry.trackKey(source.config.id, "fixture"), song.videoId)

        val stream = source.stream("fixture", StreamRequest.Capped(128)) ?: error("Expected a capped stream.")
        assertEquals("https://media.example/aac-128/song.m4a?token=y", stream.url)
        assertEquals(128, stream.format.kbps)
        assertEquals("aac", stream.format.codec)
        assertEquals(180, stream.durationSec)
        assertEquals(listOf("search", "mp3"), calls.map { it.first })
    }

    @Test
    fun rejectsNonHttpsArtworkAndLosslessRequests() = runTest {
        val row = """{"id":"fixture","song":"Example","photo":"http://img.example/cover.jpg","hq_link":"https://media.example/aac-256/song.m4a"}"""
        val source = RadioJavanSource(SourceConfig(kind = SourceKind.RADIOJAVAN)) { _, _ -> """{"mp3s":[$row]}""" }

        assertEquals(null, source.search("Example", 1, false, null).single().thumbnailUrl)
        assertNull(source.stream("fixture", StreamRequest.Lossless))
        assertFalse(SourceKind.RADIOJAVAN.canServeLossless)
    }
}
