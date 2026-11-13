package com.bxane.nuvx.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue









class RemoteSongTest {



    @Test
    fun `a name with one separator is an artist and a title`() {
        assertEquals("Pink Floyd" to "Time", RemoteSong.splitArtistTitle("Pink Floyd - Time"))
    }

    @Test
    fun `a name with no separator is all title`() {
        assertEquals(null to "Time", RemoteSong.splitArtistTitle("Time"))
    }

    @Test
    fun `only the first separator is a separator`() {



        assertEquals("Bach" to "Symphony No. 5 - Allegro", RemoteSong.splitArtistTitle("Bach - Symphony No. 5 - Allegro"))
    }

    @Test
    fun `a separator with nothing before it is not one`() {
        assertEquals(null to "Time", RemoteSong.splitArtistTitle(" - Time"))
        assertEquals(null to "Time", RemoteSong.splitArtistTitle("   - Time"))
    }

    @Test
    fun `a separator with nothing after it leaves no title`() {


        assertEquals("Pink Floyd" to null, RemoteSong.splitArtistTitle("Pink Floyd - "))
    }

    @Test
    fun `a hyphen without spaces is not a separator`() {


        assertEquals(null to "01-Intro", RemoteSong.splitArtistTitle("01-Intro"))
    }

    @Test
    fun `surrounding space is trimmed off each half`() {
        assertEquals("Pink Floyd" to "Time", RemoteSong.splitArtistTitle("  Pink Floyd   -   Time  "))
    }



    @Test
    fun `a row takes its name from the filename and its address from the listing`() {
        val song = song(fileName = "Pink Floyd - Time.flac", album = "The Dark Side of the Moon")
        assertEquals("Time", song.title)
        assertEquals("Pink Floyd", song.artist)
        assertEquals("The Dark Side of the Moon", song.albumName)


        assertEquals("https://dav.example.com/Music/Pink%20Floyd/Time.flac", song.localPath)
    }

    @Test
    fun `a row with no artist in its name says so rather than showing nothing`() {
        assertEquals(WebDavConfig.UNKNOWN_ARTIST, song(fileName = "Intro.mp3").artist)
    }

    @Test
    fun `a row is never given a thumbnail that is not there`() {


        assertNull(song(fileName = "Time.flac").thumbnailUrl)
        assertNull(song(fileName = "Time.flac").durationText)
    }

    @Test
    fun `the extension is not part of the title`() {
        assertEquals("Time", song(fileName = "Pink Floyd - Time.flac").title)

        assertEquals("Time (Part 1.5)", song(fileName = "Pink Floyd - Time (Part 1.5).flac").title)
    }

    @Test
    fun `a filename with no extension keeps every character of itself`() {


        assertEquals("Time", song(fileName = "Time").title)
        assertEquals("Pink Floyd", song(fileName = "Pink Floyd - Time").artist)
        assertEquals("Time", song(fileName = "Pink Floyd - Time").title)

        assertEquals(".hidden", song(fileName = ".hidden").title)
    }

    @Test
    fun `a file at the root of a share has no album`() {


        assertNull(song(fileName = "Time.flac", album = null).albumName)
        assertNull(song(fileName = "Time.flac", album = "  ").albumName)
    }

    @Test
    fun `a non-ascii name survives being decoded`() {



        val song = song(fileName = "Björk - Jóga.flac", album = "Homogénic")
        assertEquals("Jóga", song.title)
        assertEquals("Björk", song.artist)
        assertEquals("Homogénic", song.albumName)
    }

    @Test
    fun `a row with a non-ascii name round trips through its id`() {



        val url = WebDavConfig.joinUrl("https://dav.example.com/Music", "Björk - Jóga.flac")
        assertEquals("https://dav.example.com/Music/Bj%C3%B6rk%20-%20J%C3%B3ga.flac", url)
        assertEquals("Björk - Jóga.flac", WebDavConfig.fileNameOf(url))
        assertTrue(url.endsWith(".flac"))
    }

    private fun song(
        fileName: String,
        album: String? = null,
        url: String = "https://dav.example.com/Music/Pink%20Floyd/Time.flac",
    ) = RemoteSong.build(
        videoId = WebDavConfig.idFor(url),
        streamUrl = url,
        fileName = WebDavConfig.fileNameOf(WebDavConfig.joinUrl("https://dav.example.com/Music", fileName)),
        albumName = album,
    )
}
