package com.licht.nuvx

import com.licht.nuvx.data.PlaylistExport
import com.licht.nuvx.data.PlaylistImport
import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {
    private val songs = listOf(
        Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null),
        Song("kJQP7kiw5Fk", "Despacito, \"Remix\"", "Luis Fonsi", null),
        Song("dQw4w9WgXcQ", "=SUM(A1)", "-Formula Artist", null),
        Song("9bZkp7q19f0", "Gangnam Style", "", null),
    )

    @Test
    fun m3uExportReadsBackInTheSameOrder() {
        val back = PlaylistImport.parse(PlaylistExport.m3u("Road trip", songs))
        assertEquals("Road trip", back.title)
        assertEquals(songs.map { it.videoId }, back.songs.map { it.videoId })
        assertEquals(songs.map { it.title }, back.songs.map { it.title })
        assertEquals(songs.map { it.artist }, back.songs.map { it.artist })
        assertEquals(0, back.unmatched)
        assertEquals(0, back.wanted)
    }

    @Test
    fun csvExportReadsBackWithQuotesAndFormulaGuardUndone() {
        val back = PlaylistImport.parse(PlaylistExport.csv(songs))
        assertEquals(songs.map { it.videoId }, back.songs.map { it.videoId })
        assertEquals(songs.map { it.title }, back.songs.map { it.title })
        assertEquals(songs.map { it.artist }, back.songs.map { it.artist })
    }

    @Test
    fun otherPlayersFilesKeepLinksAndQueueTitlesForMatching() {
        val m3u = listOf(
            "#EXTM3U",
            "#EXTINF:215,Adele - Hello",
            "/sdcard/Music/song.mp3",
            "https://youtu.be/kJQP7kiw5Fk",
            "",
            "https://www.youtube.com/watch?list=x&v=9bZkp7q19f0",
            "/no/name.mp3",
        ).joinToString("\n")
        val fromM3u = PlaylistImport.parse(m3u)
        assertEquals(listOf("kJQP7kiw5Fk", "9bZkp7q19f0"), fromM3u.songs.map { it.videoId })
        assertEquals(PlaylistImport.Entry.Wanted("Hello", "Adele", 215), fromM3u.entries.first())
        assertEquals(1, fromM3u.unmatched)

        val csv = "﻿Track Name,Artist Name(s),Duration (ms)\r\nHello,\"Adele, Someone\",295000\r\n"
        val fromCsv = PlaylistImport.parse(csv)
        assertEquals(listOf(PlaylistImport.Entry.Wanted("Hello", "Adele", 295)), fromCsv.entries)
        assertEquals(0, fromCsv.unmatched)
    }

    @Test
    fun resolvingKeepsFileOrderAndCountsMisses() = runBlocking {
        val found = Song("kJQP7kiw5Fk", "Despacito", "Luis Fonsi", null)
        val entries = listOf(
            PlaylistImport.Entry.Wanted("Hello", "Adele", null),
            PlaylistImport.Entry.Found(found),
            PlaylistImport.Entry.Wanted("Nothing like it", "Nobody", null),
            PlaylistImport.Entry.Wanted("Crash", "Boom", null),
        )
        val hello = Song("YQHsXMglC9A", "Hello", "Adele", null)
        val (resolved, missed) = PlaylistImport.resolve(entries) { wanted ->
            when (wanted.title) {
                "Hello" -> hello
                "Crash" -> error("network down")
                else -> null
            }
        }
        assertEquals(listOf(hello, found), resolved)
        assertEquals(2, missed)
    }

    @Test
    fun junkIsNotASong() {
        val back = PlaylistImport.parse("just some text\nwatch?v=tooShort\n")
        assertTrue(back.entries.isEmpty())
        assertEquals(2, back.unmatched)
    }
}
