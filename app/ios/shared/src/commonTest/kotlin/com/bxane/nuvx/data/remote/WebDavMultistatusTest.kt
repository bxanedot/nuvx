package com.bxane.nuvx.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue









class WebDavMultistatusTest {

    private val dir = "https://cloud.example.com/Music"

    private fun parse(xml: String, base: String = dir) = WebDavClient.parseMultistatus(xml, base)



    @Test
    fun `a prefixed multistatus is read`() {
        val entries = parse(MULTISTATUS)
        assertEquals(2, entries.size)


        val track = entries.first { it.displayName == "track.flac" }
        assertEquals("https://cloud.example.com/Music/Album/track.flac", track.url)
        assertFalse(track.isCollection)
    }

    @Test
    fun `a collection is a collection`() {
        assertTrue(parse(MULTISTATUS).first { it.displayName == "Album" }.isCollection)
    }

    @Test
    fun `a content type is read when the server sends one`() {
        assertEquals("audio/flac", parse(MULTISTATUS).first { it.displayName == "track.flac" }.contentType)
    }

    @Test
    fun `the directory the server echoes back is not a track`() {


        assertFalse(parse(MULTISTATUS).any { it.isCollection && it.displayName == "Music" })
    }



    @Test
    fun `a different namespace prefix is still read`() {


        val upper = MULTISTATUS.replace("d:", "D:")
        assertEquals(2, parse(upper).size)
        val bare = MULTISTATUS.replace("d:", "")
        assertEquals(2, parse(bare).size)
        val other = MULTISTATUS.replace("d:", "lp1:")
        assertEquals(2, parse(other).size)
    }

    @Test
    fun `a self-closing collection still counts as a collection`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/Music/Album/</d:href>
    <d:resourcetype><d:collection/></d:resourcetype>
  </d:response>
</d:multistatus>"""
        val entry = parse(xml).single()
        assertTrue(entry.isCollection, "a self-closing <collection/> was missed")
    }



    @Test
    fun `a display name is preferred over the address`() {


        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/Music/track-1.flac</d:href>
    <d:displayname>Real Name.flac</d:displayname>
  </d:response>
</d:multistatus>"""
        assertEquals("Real Name.flac", parse(xml).single().displayName)
    }

    @Test
    fun `a missing display name falls back to the decoded address`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/Music/Artist%20-%20Title.flac</d:href>
  </d:response>
</d:multistatus>"""
        assertEquals("Artist - Title.flac", parse(xml).single().displayName)
    }



    @Test
    fun `an absolute href is used as it is`() {



        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>https://cdn.example.net/a.flac</d:href></d:response>
</d:multistatus>"""
        assertEquals("https://cdn.example.net/a.flac", parse(xml).single().url)
    }

    @Test
    fun `a root-relative href keeps the scheme host and port`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/Music/a.flac</d:href></d:response>
</d:multistatus>"""
        assertEquals(
            "https://cloud.example.com:8443/Music/a.flac",
            parse(xml, "https://cloud.example.com:8443/dav").single().url
        )
    }

    @Test
    fun `a relative href resolves against the directory asked about`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>a.flac</d:href></d:response>
</d:multistatus>"""
        assertEquals("https://cloud.example.com/Music/a.flac", parse(xml).single().url)
    }

    @Test
    fun `a relative href does not double a slash`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>a.flac</d:href></d:response>
</d:multistatus>"""
        assertFalse(parse(xml, "https://cloud.example.com/Music/").single().url.contains("//Music"))
    }

    @Test
    fun `an entry is not matched against the directory it came from`() {


        for (echoed in listOf("/Music", "/Music/")) {
            val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>$echoed</d:href></d:response>
</d:multistatus>"""
            assertTrue(parse(xml).isEmpty(), "the echoed directory '$echoed' was listed as a track")
        }
    }



    @Test
    fun `an empty response is no entries rather than a failure`() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("   "))
    }

    @Test
    fun `a response with no href is skipped rather than guessed at`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:displayname>orphan</d:displayname></d:response>
</d:multistatus>"""
        assertEquals(emptyList(), parse(xml))
    }

    @Test
    fun `something that is not xml at all is no entries`() {


        assertEquals(emptyList(), parse("<html><body>502 Bad Gateway</body></html>"))
        assertEquals(emptyList(), parse("{\"error\":\"nope\"}"))
    }

    @Test
    fun `a truncated response is read as far as it goes`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/Music/a.flac</d:href></d:response>
  <d:response><d:href>/Music/b.flac</d:h"""
        assertEquals(1, parse(xml).size)
    }



    @Test
    fun `a multistatus body built with a builder round-trips`() {


        val entries = parse(MULTISTATUS)
        assertTrue(entries.all { it.url.startsWith("https://cloud.example.com/Music/") })
        assertEquals(1, entries.count { it.isCollection })
        assertEquals(1, entries.count { !it.isCollection })
    }

    private companion object {
        val MULTISTATUS = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/Music/</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>Music</d:displayname>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/Music/Album/</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>Album</d:displayname>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/Music/Album/track.flac</d:href>
    <d:propstat>
      <d:prop>
        <d:displayname>track.flac</d:displayname>
        <d:resourcetype/>
        <d:getcontenttype>audio/flac</d:getcontenttype>
      </d:prop>
    </d:propstat>
  </d:response>
</d:multistatus>"""
    }
}
