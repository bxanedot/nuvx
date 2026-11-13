package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue









class JamInviteLinkTest {

    private fun code() = "ABC123"



    @Test
    fun `a custom-scheme link in the path is read`() {
        assertEquals(code(), JamInviteLink.parseInvite("nuvx://party/ABC123")?.code)
    }

    @Test
    fun `a custom-scheme link in the query is read`() {
        assertEquals(code(), JamInviteLink.parseInvite("nuvx://party?code=ABC123")?.code)
    }

    @Test
    fun `a lowercase code is accepted and upper-cased`() {


        assertEquals(code(), JamInviteLink.parseInvite("nuvx://party/abc123")?.code)
    }

    @Test
    fun `a trailing slash is tolerated`() {
        assertEquals(code(), JamInviteLink.parseInvite("nuvx://party/ABC123/")?.code)
    }

    @Test
    fun `a code with a stray character is cleaned rather than refused`() {
        assertEquals("ABC123", JamInviteLink.cleanCode("ABC-123"))
        assertEquals("ABC123", JamInviteLink.cleanCode(" ABC123 "))
    }



    @Test
    fun `a web invite is read`() {
        assertEquals(code(), JamInviteLink.parseInvite("https://nuvx.lol/invite/ABC123")?.code)
    }

    @Test
    fun `a web invite with a trailing slash is read`() {
        assertEquals(
            code(),
            JamInviteLink.parseInvite("https://nuvx.lol/invite/ABC123/")?.code,
        )
    }

    @Test
    fun `a web invite with other content around it is not`() {


        assertNull(JamInviteLink.parseInvite("https://nuvx.lol/invite/ABC123/more"))
    }



    @Test
    fun `a code of the wrong length is not a code`() {



        assertNull(JamInviteLink.cleanCode("ABC12"))
        assertNull(JamInviteLink.cleanCode("ABC1234"))
        assertNull(JamInviteLink.cleanCode(""))
    }

    @Test
    fun `another app's link is not an invite`() {
        assertNull(JamInviteLink.parseInvite("spotify:track:abc"))
        assertNull(JamInviteLink.parseInvite("https://example.invalid/invite/ABC123"))
    }

    @Test
    fun `nothing is not an invite`() {
        assertNull(JamInviteLink.parseInvite(null))
        assertNull(JamInviteLink.parseInvite(""))
        assertNull(JamInviteLink.parseInvite("   "))
        assertNull(JamInviteLink.parseInvite("not a url at all"))
    }

    @Test
    fun `the custom scheme with the wrong host is not an invite`() {
        assertNull(JamInviteLink.parseInvite("nuvx://somethingelse/ABC123"))
    }

    @Test
    fun `plain http is not the invite domain`() {


        assertNull(JamInviteLink.parseInvite("http://nuvx.lol/invite/ABC123"))
    }



    @Test
    fun `a server on the invite is carried through`() {
        val invite = JamInviteLink.parseInvite("nuvx://party/ABC123?server=https://jam.example")
        assertEquals("https://jam.example", invite?.serverUrl)
    }

    @Test
    fun `a server without a scheme is filled in rather than refused`() {


        assertEquals("https://jam.example", JamInviteLink.sanitizeServerUrl("jam.example"))
    }

    @Test
    fun `a server keeps the scheme it was given`() {
        assertEquals("http://jam.example", JamInviteLink.sanitizeServerUrl("http://jam.example"))
        assertEquals("https://jam.example", JamInviteLink.sanitizeServerUrl("https://jam.example"))
    }

    @Test
    fun `a trailing slash on the server is dropped`() {
        assertEquals("https://jam.example", JamInviteLink.sanitizeServerUrl("https://jam.example/"))
    }

    @Test
    fun `a server with no host is refused`() {

        assertNull(JamInviteLink.sanitizeServerUrl("https://"))
        assertNull(JamInviteLink.sanitizeServerUrl("   "))
        assertNull(JamInviteLink.sanitizeServerUrl(null))
    }

    @Test
    fun `a server with a space in it is refused`() {
        assertNull(JamInviteLink.sanitizeServerUrl("https://jam example"))
    }

    @Test
    fun `an invite with no server carries none`() {
        assertNull(JamInviteLink.parseInvite("nuvx://party/ABC123")?.serverUrl)
    }



    @Test
    fun `a parameter is matched ignoring case`() {
        assertEquals("ABC123", JamInviteLink.queryParam("Code=ABC123", "code"))
    }

    @Test
    fun `a percent-encoded parameter is decoded`() {
        assertEquals("https://jam.example", JamInviteLink.queryParam("server=https%3A%2F%2Fjam.example", "server"))
    }

    @Test
    fun `a plus is left alone`() {


        assertEquals("a+b", JamInviteLink.queryParam("k=a+b", "k"))
    }

    @Test
    fun `a malformed escape is passed through rather than dropped`() {
        assertEquals("100%", JamInviteLink.queryParam("k=100%", "k"))
    }

    @Test
    fun `a parameter with no value is not a parameter`() {
        assertNull(JamInviteLink.queryParam("code", "code"))
        assertNull(JamInviteLink.queryParam("", "code"))
    }



    @Test
    fun `an invite-looking link is recognised even if the code is wrong`() {


        assertTrue(JamInviteLink.looksLikeInvite("nuvx://party/BADCODE"))
        assertTrue(JamInviteLink.looksLikeInvite("https://nuvx.lol/invite/ABC123"))
    }

    @Test
    fun `an unrelated link is not offered`() {
        assertFalse(JamInviteLink.looksLikeInvite("https://example.invalid/anything"))
        assertFalse(JamInviteLink.looksLikeInvite(null))
    }
}
