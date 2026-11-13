package com.bxane.nuvx.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue







class WebDavConfigTest {



    @Test
    fun `an address with a scheme is kept as it is`() {
        assertEquals("https://cloud.example.com/dav", WebDavConfig.normalizeUrl("https://cloud.example.com/dav"))
        assertEquals("http://192.168.0.9:5005/dav", WebDavConfig.normalizeUrl("http://192.168.0.9:5005/dav"))
    }

    @Test
    fun `an address without a scheme becomes https`() {


        assertEquals("https://cloud.example.com", WebDavConfig.normalizeUrl("cloud.example.com"))
    }

    @Test
    fun `a trailing slash is one slash fewer and no more`() {
        assertEquals("https://cloud.example.com/dav", WebDavConfig.normalizeUrl("https://cloud.example.com/dav/"))
        assertEquals("https://cloud.example.com/dav", WebDavConfig.normalizeUrl("  https://cloud.example.com/dav//  "))
    }

    @Test
    fun `nothing configured is nothing`() {
        assertEquals("", WebDavConfig.normalizeUrl(""))
        assertEquals("", WebDavConfig.normalizeUrl("   "))


        assertEquals("", WebDavConfig.normalizeUrl("///"))
        assertFalse(WebDavConfig.isConfigured("///"))
    }

    @Test
    fun `an address with a space in it is not configured`() {



        assertFalse(WebDavConfig.isConfigured("not a url"))
        assertFalse(WebDavConfig.isConfigured("https://"))
        assertFalse(WebDavConfig.isConfigured("cloud.example.com/ a b"))
    }

    @Test
    fun `a real address is configured`() {
        assertTrue(WebDavConfig.isConfigured("cloud.example.com"))
        assertTrue(WebDavConfig.isConfigured("https://cloud.example.com/remote.php/dav/files/me/Music"))
    }



    @Test
    fun `the host is lower-cased and carries no port`() {
        assertEquals("cloud.example.com", WebDavConfig.hostOf("https://Cloud.Example.COM:8443/dav"))
    }

    @Test
    fun `a host and no port is the same host as a host and a port`() {



        assertEquals(WebDavConfig.hostOf("https://cloud.example.com/dav"), WebDavConfig.hostOf("https://cloud.example.com:8443/dav"))
    }

    @Test
    fun `a path with a dot in it does not become part of the host`() {
        assertEquals("cloud.example.com", WebDavConfig.hostOf("https://cloud.example.com/remote.php/dav/files/me/Music"))
    }

    @Test
    fun `a userinfo before the host is not mistaken for it`() {
        assertEquals("cloud.example.com", WebDavConfig.hostOf("https://sam:pw@cloud.example.com/dav"))
    }

    @Test
    fun `an ipv6 literal keeps its brackets`() {
        assertEquals("[2001:db8::1]", WebDavConfig.hostOf("https://[2001:DB8::1]:8443/dav"))
    }

    @Test
    fun `nothing has no host`() {
        assertNull(WebDavConfig.hostOf(""))
        assertNull(WebDavConfig.hostOf("   "))

        assertEquals("cloud.example.com", WebDavConfig.hostOf("cloud.example.com"))
    }

    @Test
    fun `a scheme with no host is not an address`() {



        assertNull(WebDavConfig.hostOf("https://"))
        assertFalse(WebDavConfig.isConfigured("https://"))
        assertFalse(WebDavConfig.isConfigured("http://"))
    }



    @Test
    fun `a credential is basic auth over base64`() {


        assertEquals("Basic dXNlcjpwYXNz", WebDavConfig.basicAuthHeader("user", "pass"))
    }

    @Test
    fun `a credential with a non-ascii password is utf-8`() {
        assertNotNull(WebDavConfig.basicAuthHeader("sam", "pässwörd"))
    }

    @Test
    fun `no credential at all is no header`() {


        assertNull(WebDavConfig.basicAuthHeader("", ""))
        assertNull(WebDavConfig.basicAuthHeader("  ", "  "))
    }

    @Test
    fun `a username with no password is still a credential`() {

        assertNotNull(WebDavConfig.basicAuthHeader("sam", ""))
    }



    @Test
    fun `a track id is the address and a prefix`() {



        assertEquals("webdav:https://cloud.example.com/a.flac", WebDavConfig.idFor("https://cloud.example.com/a.flac"))
    }

    @Test
    fun `a webdav id is recognisable and a youtube one is not`() {
        assertTrue(WebDavConfig.isWebDavId("webdav:https://cloud.example.com/a.flac"))
        assertFalse(WebDavConfig.isWebDavId("dQw4w9WgXcQ"))
        assertFalse(WebDavConfig.isWebDavId(""))
    }

    @Test
    fun `a track id reads back as the address it came from`() {
        val url = "https://cloud.example.com/Music/Artist/Album/track.flac"
        assertEquals(url, WebDavConfig.fileUrlOf(WebDavConfig.idFor(url)))
    }

    @Test
    fun `a prefixed id that is not an http address plays as nothing`() {



        assertNull(WebDavConfig.fileUrlOf("webdav:file:///etc/passwd"))
        assertNull(WebDavConfig.fileUrlOf("webdav:smb://host/share/a.flac"))
        assertNull(WebDavConfig.fileUrlOf("dQw4w9WgXcQ"))
    }



    @Test
    fun `an audio file is one the extensions say it is`() {
        assertTrue(WebDavConfig.isAudioFile("track.flac"))
        assertTrue(WebDavConfig.isAudioFile("TRACK.FLAC"))
        assertTrue(WebDavConfig.isAudioFile("a.b.c.mp3"))
    }

    @Test
    fun `a picture is not an audio file and the reverse`() {
        assertTrue(WebDavConfig.isImageFile("cover.jpg"))
        assertFalse(WebDavConfig.isImageFile("cover.png.tmp"))
        assertFalse(WebDavConfig.isAudioFile("cover.jpg"))
    }

    @Test
    fun `a file with no extension is neither`() {


        assertFalse(WebDavConfig.isAudioFile("README"))
        assertFalse(WebDavConfig.isImageFile("README"))
        assertFalse(WebDavConfig.isAudioFile("trailing."))
    }

    @Test
    fun `a filename is the last segment and is decoded`() {
        assertEquals("Artist - Title.flac", WebDavConfig.fileNameOf("https://cloud.example.com/Music/Artist%20-%20Title.flac"))
    }

    @Test
    fun `a filename ignores a query or a fragment`() {
        assertEquals("a.flac", WebDavConfig.fileNameOf("https://cloud.example.com/a.flac?token=x"))
        assertEquals("a.flac", WebDavConfig.fileNameOf("https://cloud.example.com/a.flac#t=10"))
    }

    @Test
    fun `a malformed escape is left alone rather than dropped`() {


        assertEquals("100% Pure.flac", WebDavConfig.fileNameOf("https://cloud.example.com/100%25%20Pure.flac"))
        assertEquals("50%off.flac", WebDavConfig.fileNameOf("https://cloud.example.com/50%off.flac"))
    }

    @Test
    fun `the album is the folder a file sits in`() {


        assertEquals("Album", WebDavConfig.folderNameOf("https://cloud.example.com/Music/Artist/Album/song.flac"))
    }

    @Test
    fun `a file at the root has no album rather than an empty one`() {


        assertNull(WebDavConfig.folderNameOf("https://cloud.example.com/song.flac"))
        assertNull(WebDavConfig.folderNameOf("https://cloud.example.com/"))
        assertEquals("Music", WebDavConfig.folderNameOf("https://cloud.example.com/Music/song.flac"))
    }

    @Test
    fun `an album is decoded and trimmed`() {
        assertEquals("Deluxe Edition", WebDavConfig.folderNameOf("https://cloud.example.com/Music/Deluxe%20Edition/song.flac"))
    }



    @Test
    fun `an upload is named artist dash title`() {
        assertEquals("Sam - Title.flac", WebDavConfig.uploadFileName("Title", "Sam", "flac"))
    }

    @Test
    fun `an upload with no artist worth naming is the title alone`() {
        assertEquals("Title.flac", WebDavConfig.uploadFileName("Title", "", "flac"))
        assertEquals("Title.flac", WebDavConfig.uploadFileName("Title", "Unknown Artist", "flac"))
    }

    @Test
    fun `a path separator in a title never becomes a folder`() {
        assertEquals("Title _ Live.flac", WebDavConfig.uploadFileName("Title / Live", "Unknown Artist", "flac"))
        assertEquals("Title _ Live.flac", WebDavConfig.uploadFileName("Title \\ Live", "Unknown Artist", "flac"))
    }

    @Test
    fun `a control character in a title is replaced rather than sent`() {
        val name = WebDavConfig.uploadFileName("Ti\u0000tle", "Unknown Artist", "mp3")
        assertFalse(name.contains('\u0000'))
    }

    @Test
    fun `an extension is lower-cased and a leading dot is not doubled`() {
        assertEquals("Sam - Title.flac", WebDavConfig.uploadFileName("Title", "Sam", ".FLAC"))
        assertEquals("Sam - Title.mp3", WebDavConfig.uploadFileName("Title", "Sam", ""))
    }

    @Test
    fun `a very long name is cut and still carries its extension`() {
        val name = WebDavConfig.uploadFileName("x".repeat(400), "Unknown Artist", "flac")
        assertTrue(name.endsWith(".flac"))
        assertTrue(name.length < 130, "not cut: ${name.length}")
    }

    @Test
    fun `a name that is only dots is not an empty filename`() {
        assertEquals("track.mp3", WebDavConfig.uploadFileName("...", "Unknown Artist", "mp3"))
    }



    @Test
    fun `an untaken name is used as it is`() {
        assertEquals("Song.mp3", WebDavConfig.resolveNumberedName("Song", "mp3", emptySet()))
    }

    @Test
    fun `a taken name is numbered until one is free`() {
        assertEquals(
            "Song (1).mp3",
            WebDavConfig.resolveNumberedName("Song", "mp3", setOf("Song.mp3"))
        )
        assertEquals(
            "Song (2).mp3",
            WebDavConfig.resolveNumberedName("Song", "mp3", setOf("Song.mp3", "Song (1).mp3"))
        )
    }

    @Test
    fun `a taken name is compared without regard to case`() {


        assertEquals("Song (1).mp3", WebDavConfig.resolveNumberedName("Song", "mp3", setOf("song.mp3")))
    }

    @Test
    fun `the search gives up rather than looping forever`() {

        val taken = (0..1000).map { "Song ($it).mp3" }.toSet() + "Song.mp3"
        assertEquals("Song (1000).mp3", WebDavConfig.resolveNumberedName("Song", "mp3", taken))
    }



    @Test
    fun `a segment is joined and escaped as a path`() {
        assertEquals(
            "https://cloud.example.com/Music/Artist%20-%20Title.mp3",
            WebDavConfig.joinUrl("https://cloud.example.com/Music/", "Artist - Title.mp3")
        )
    }

    @Test
    fun `a space in a path is a space and never a plus`() {


        val url = WebDavConfig.joinUrl("https://cloud.example.com/M", "a b.flac")
        assertTrue(url.endsWith("/a%20b.flac"), url)
        assertFalse(url.contains('+'))
    }

    @Test
    fun `a character that would change the path is escaped`() {


        val url = WebDavConfig.joinUrl("https://cloud.example.com/M", "a#b?c.flac")
        assertEquals("https://cloud.example.com/M/a%23b%3Fc.flac", url)
    }
}
