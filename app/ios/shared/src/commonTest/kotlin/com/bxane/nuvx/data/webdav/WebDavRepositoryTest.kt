package com.bxane.nuvx.data.webdav

import com.bxane.nuvx.data.remote.WebDavClient
import com.bxane.nuvx.data.remote.WebDavConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue









class WebDavRepositoryTest {

    private val dir = "https://dav.example.com/Music"


    private val albumDir = "$dir/Pink%20Floyd/The%20Dark%20Side%20of%20the%20Moon"

    private fun entry(
        name: String,
        displayName: String = name,
        folder: String = albumDir,
    ) = WebDavClient.Entry(
        url = WebDavConfig.joinUrl(folder, name),
        displayName = displayName,
        isCollection = false,
    )

    private fun song(name: String, displayName: String = name, cover: String? = null, folder: String = albumDir) =
        with(WebDavRepository) { entry(name, displayName, folder).toSong(cover) }



    @Test
    fun `a server's own name for a file beats the one decoded out of its address`() {


        val song = song("track-1.flac", displayName = "Time.flac")
        assertEquals("Time", song.title)


        assertEquals(WebDavConfig.UNKNOWN_ARTIST, song.artist)
    }

    @Test
    fun `a display name with a separator in it gives an artist and a title`() {
        val song = song("track-1.flac", displayName = "Björk - Jóga.flac")
        assertEquals("Jóga", song.title)
        assertEquals("Björk", song.artist)
    }

    @Test
    fun `a display name that is only an extension falls back to the address`() {



        val song = song("Björk - Jóga.flac", displayName = ".flac")
        assertEquals("Jóga", song.title)
        assertEquals("Björk", song.artist)
    }

    @Test
    fun `a display name with no artist in it keeps the address's artist`() {
        val song = song("Pink Floyd - Time.flac", displayName = "Time (Remastered).flac")
        assertEquals("Time (Remastered)", song.title)
        assertEquals("Pink Floyd", song.artist)
    }

    @Test
    fun `a display name with no artist and an address with none says so`() {
        val song = song("intro.flac", displayName = "Intro (live).flac")
        assertEquals("Intro (live)", song.title)
        assertEquals(WebDavConfig.UNKNOWN_ARTIST, song.artist)
    }

    @Test
    fun `a non-ascii name survives the server and the address`() {
        val song = song("%E6%97%A5%E6%9C%AC%E8%AA%9E.flac", displayName = "日本語 - 歌.flac")
        assertEquals("歌", song.title)
        assertEquals("日本語", song.artist)
    }



    @Test
    fun `the album is the folder the file sits in`() {
        assertEquals("The Dark Side of the Moon", song("Time.flac").albumName)
    }

    @Test
    fun `a file at the root of a share has no album`() {


        val atRoot = WebDavClient.Entry(
            url = "https://dav.example.com/Time.flac",
            displayName = "Time.flac",
            isCollection = false,
        )
        assertNull(with(WebDavRepository) { atRoot.toSong() }.albumName)
    }

    @Test
    fun `the play address is the address the listing gave`() {
        val song = song("Time.flac")
        assertEquals(entry("Time.flac").url, song.localPath)
        assertTrue(song.localPath!!.startsWith("https://dav.example.com/"))
    }

    @Test
    fun `the id is the prefixed address so two libraries cannot collide`() {


        val song = song("Time.flac")
        assertEquals(WebDavConfig.idFor(entry("Time.flac").url), song.videoId)
        assertTrue(WebDavConfig.isWebDavId(song.videoId))
        assertEquals(entry("Time.flac").url, WebDavConfig.fileUrlOf(song.videoId))
    }

    @Test
    fun `a cover filed beside the file is the row's thumbnail`() {
        val cover = "https://dav.example.com/Music/Pink%20Floyd/cover.jpg"
        assertEquals(cover, song("Time.flac", cover = cover).thumbnailUrl)


        assertNull(song("Time.flac").thumbnailUrl)
    }



    @Test
    fun `a cover is matched to the folder it is filed in`() {




        val url = entry("Time.flac").url
        val sameFolder = WebDavConfig.joinUrl(albumDir, "cover.jpg")
        val otherFolder = WebDavConfig.joinUrl("$dir/Pink%20Floyd/Another%20Album", "cover.jpg")
        assertEquals(WebDavConfig.dirKeyOf(url), WebDavConfig.dirKeyOf(sameFolder))
        assertTrue(WebDavConfig.dirKeyOf(url) != WebDavConfig.dirKeyOf(otherFolder))
    }

    @Test
    fun `two servers on one machine are two libraries`() {



        assertTrue(
            WebDavConfig.dirKeyOf("http://nas.local:5005/music/cover.jpg")
                != WebDavConfig.dirKeyOf("http://nas.local:8096/music/cover.jpg"),
        )
        assertEquals("nas.local", WebDavConfig.hostOf("http://nas.local:5005/music"))
    }
}
