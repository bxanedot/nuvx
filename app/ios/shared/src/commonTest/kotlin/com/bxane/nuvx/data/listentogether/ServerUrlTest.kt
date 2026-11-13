package com.bxane.nuvx.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue










class ServerUrlTest {

    private fun valid(raw: String?): String {
        val result = ServerUrl.parseAndNormalize(raw)
        assertIs<ServerUrlValidationResult.Valid>(result, "expected valid: $raw -> $result")
        return result.normalizedUrl
    }

    private fun error(raw: String?): ServerUrlError {
        val result = ServerUrl.parseAndNormalize(raw)
        assertIs<ServerUrlValidationResult.Invalid>(result, "expected invalid: $raw -> $result")
        return result.error
    }



    @Test
    fun `an empty address is valid and means no server`() {


        assertEquals("", valid(""))
        assertEquals("", valid("   "))
        assertEquals("", valid(null))
    }



    @Test
    fun `a missing scheme is filled in with https`() {
        assertEquals("https://jam.example", valid("jam.example"))
    }

    @Test
    fun `a trailing slash is removed`() {


        assertEquals("https://jam.example", valid("https://jam.example/"))
        assertEquals("https://jam.example", valid("jam.example/"))
    }

    @Test
    fun `a host is lower-cased so one server does not look like two`() {
        assertEquals("https://jam.example", valid("https://JAM.Example"))
        assertEquals("http://jam.example", valid("HTTP://JAM.example"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://jam.example", valid("  https://jam.example  "))
    }

    @Test
    fun `a sub-path is kept because some deployments live under one`() {
        assertEquals("https://example.com/jam", valid("https://example.com/jam"))
        assertEquals("https://example.com/jam", valid("https://example.com/jam/"))
        assertEquals("https://example.com/jam", valid("https://example.com//jam//"))
    }

    @Test
    fun `a port is kept`() {
        assertEquals("https://jam.example:8443", valid("jam.example:8443"))
        assertEquals("http://192.168.0.252:8000", valid("http://192.168.0.252:8000"))
    }

    @Test
    fun `the scheme's own default port is dropped`() {



        assertEquals("https://jam.example", valid("https://jam.example:443"))
        assertEquals("http://jam.example", valid("http://jam.example:80"))

        assertEquals("https://jam.example:80", valid("https://jam.example:80"))
    }

    @Test
    fun `a lan address is a valid host`() {

        assertEquals("http://192.168.0.252:8000", valid("http://192.168.0.252:8000"))
        assertEquals("https://127.0.0.1:8000", valid("127.0.0.1:8000"))
    }

    @Test
    fun `localhost is allowed though it is one label`() {


        assertEquals("http://localhost:8000", valid("http://localhost:8000"))
        assertEquals("http://localhost:8000", valid("http://LOCALHOST:8000"))
    }



    @Test
    fun `whitespace anywhere is refused`() {

        assertEquals(ServerUrlError.Whitespace, error("https://jam example"))
        assertEquals(ServerUrlError.Whitespace, error("https://jam.example\tx"))
    }

    @Test
    fun `a scheme that is not http is refused`() {


        assertEquals(ServerUrlError.InvalidScheme, error("ftp://jam.example"))
        assertEquals(ServerUrlError.InvalidScheme, error("ws://jam.example"))
        assertEquals(ServerUrlError.InvalidScheme, error("file://jam.example"))
    }

    @Test
    fun `a query is refused`() {


        assertEquals(ServerUrlError.HasQuery, error("https://jam.example?token=x"))
    }

    @Test
    fun `a fragment is refused`() {
        assertEquals(ServerUrlError.HasFragment, error("https://jam.example#top"))
    }

    @Test
    fun `a dot path segment is refused`() {


        assertEquals(ServerUrlError.InvalidPath, error("https://jam.example/../admin"))
        assertEquals(ServerUrlError.InvalidPath, error("https://jam.example/a/./b"))
    }

    @Test
    fun `a port out of range is refused`() {
        assertEquals(ServerUrlError.InvalidPort, error("jam.example:0"))
        assertEquals(ServerUrlError.InvalidPort, error("jam.example:70000"))
        assertEquals(ServerUrlError.InvalidPort, error("jam.example:99999"))
    }

    @Test
    fun `a port that is not a number is refused`() {
        assertEquals(ServerUrlError.InvalidPort, error("jam.example:port"))
    }

    @Test
    fun `no host at all is refused`() {
        assertEquals(ServerUrlError.InvalidHost, error("https://"))
        assertEquals(ServerUrlError.InvalidHost, error("https:///api"))
    }

    @Test
    fun `a single-label host is refused except localhost`() {



        assertEquals(ServerUrlError.InvalidHost, error("https://jam"))
    }

    @Test
    fun `a malformed label is refused`() {



        assertEquals(ServerUrlError.InvalidHost, error("https://jam.exa_mple"))
        assertEquals(ServerUrlError.InvalidHost, error("https://jam..example"))
        assertEquals(ServerUrlError.InvalidHost, error("https://-jam.example"))
        assertEquals(ServerUrlError.InvalidHost, error("https://jam-.example"))
    }

    @Test
    fun `a leading or trailing dot on the host is refused`() {


        assertEquals(ServerUrlError.InvalidHost, error("https://jam.example."))
        assertEquals(ServerUrlError.InvalidHost, error("https://.jam.example"))
    }

    @Test
    fun `an over-long label is refused`() {
        val long = "a".repeat(64)
        assertEquals(ServerUrlError.InvalidHost, error("https://jam.$long"))
    }



    @Test
    fun `an ipv6 literal keeps its brackets and its port`() {


        assertEquals("http://[::1]:8000", valid("http://[::1]:8000"))
        assertEquals("http://[::1]", valid("http://[::1]"))
    }

    @Test
    fun `an ipv6 literal is lower-cased`() {
        assertEquals("http://[2001:db8::1]", valid("http://[2001:DB8::1]"))
    }



    @Test
    fun `the result appends a path without doubling the separator`() {


        val base = valid("https://jam.example/")
        assertEquals("https://jam.example/api/parties", base + "/api/parties")
        assertTrue(!base.contains("//api"))
    }

    @Test
    fun `every spelling of one server normalises to the same string`() {

        val spellings = listOf(
            "jam.example",
            "JAM.example",
            "https://jam.example",
            "https://jam.example/",
            "  https://jam.example/  ",
            "https://jam.example:443",
        ).map { valid(it) }
        assertEquals(1, spellings.distinct().size, "diverged: $spellings")


        assertTrue(valid("http://jam.example") != valid("https://jam.example"))
    }
}
