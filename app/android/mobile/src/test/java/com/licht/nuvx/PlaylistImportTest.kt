package com.licht.nuvx

import com.licht.nuvx.data.PlaylistExport
import com.licht.nuvx.data.PlaylistImport
import com.licht.nuvx.data.model.Song
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
    }

    @Test
    fun csvExportReadsBackWithQuotesAndFormulaGuardUndone() {
        val back = PlaylistImport.parse(PlaylistExport.csv(songs))
        assertEquals(songs.map { it.videoId }, back.songs.map { it.videoId })
        assertEquals(songs.map { it.title }, back.songs.map { it.title })
        assertEquals(songs.map { it.artist }, back.songs.map { it.artist })
    }

    @Test
    fun otherPlayersFilesKeepYouTubeLinksAndCountTheRest() {
        val m3u = "#EXTM3U\n#EXTINF:215,Local file\n/sdcard/Music/song.mp3\nhttps://youtu.be/kJQP7kiw5Fk\n\nhttps://www.youtube.com/watch?list=x&v=9bZkp7q19f0\n"
        val fromM3u = PlaylistImport.parse(m3u)
        assertEquals(listOf("kJQP7kiw5Fk", "9bZkp7q19f0"), fromM3u.songs.map { it.videoId })
        assertEquals(1, fromM3u.unmatched)

        val csv = "﻿Track Name,Artist Name(s)\r\nHello,Adele\r\n"
        val fromCsv = PlaylistImport.parse(csv)
        assertTrue(fromCsv.songs.isEmpty())
        assertEquals(1, fromCsv.unmatched)
    }

    @Test
    fun junkIsNotASong() {
        val back = PlaylistImport.parse("just some text\nwatch?v=tooShort\n")
        assertTrue(back.songs.isEmpty())
        assertEquals(2, back.unmatched)
    }
}
