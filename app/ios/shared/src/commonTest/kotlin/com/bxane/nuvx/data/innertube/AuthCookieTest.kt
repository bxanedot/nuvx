package com.bxane.nuvx.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue














class AuthCookieTest {

    @Test
    fun findsThePlainSigningSecret() {
        assertTrue(Innertube.hasApiSid("SID=abc; SAPISID=secret; HSID=def"))
    }

    @Test
    fun findsTheThirdPartySecureForm() {


        assertTrue(Innertube.hasApiSid("SID=abc; __Secure-3PAPISID=secret"))
        assertTrue(Innertube.hasApiSid("SID=abc; __Secure-1PAPISID=secret"))
    }

    @Test
    fun toleratesNoSpaceAfterEachSeparator() {
        assertTrue(Innertube.hasApiSid("SID=abc; SAPISID=secret;HSID=def"))
    }

    @Test
    fun toleratesWhitespaceAroundTheName() {
        assertTrue(Innertube.hasApiSid("  SAPISID = secret  "))
    }

    @Test
    fun rejectsAJarWithNoSigningSecret() {
        assertFalse(Innertube.hasApiSid("SID=abc; HSID=def; SSID=ghi; APISID=jkl"))
        assertFalse(Innertube.hasApiSid(""))
    }

    @Test
    fun rejectsAnEmptyValue() {


        assertFalse(Innertube.hasApiSid("SID=abc; SAPISID=; HSID=def"))
    }

    @Test
    fun matchesOnTheWholeNameNotASubstring() {
        assertFalse(Innertube.hasApiSid("APISID=secret"))
        assertFalse(Innertube.hasApiSid("NOT-SAPISID=secret"))
        assertFalse(Innertube.hasApiSid("PREF=tz=SAPISID"))
    }

    @Test
    fun extractsTheSecretForSigning() {
        assertEquals("secret", Innertube.sapisidFrom("SID=abc; SAPISID=secret; HSID=def"))
        assertEquals("secret", Innertube.sapisidFrom("SID=abc; __Secure-3PAPISID=secret"))
        assertNull(Innertube.sapisidFrom("SID=abc; HSID=def"))
    }

    @Test
    fun prefersThePlainFormWhenSeveralArePresent() {



        assertEquals("plain", Innertube.sapisidFrom("SAPISID=plain; __Secure-3PAPISID=third"))
    }

    @Test
    fun signsWithGooglesScheme() {
        val header = Innertube.sapisidHash("secret", "https://music.youtube.com")
        assertTrue(header.startsWith("SAPISIDHASH "), "was: $header")

        val payload = header.removePrefix("SAPISIDHASH ")
        val (timestamp, digest) = payload.split("_", limit = 2)
        assertTrue(timestamp.toLongOrNull() != null, "timestamp was: $timestamp")
        assertEquals(40, digest.length, "digest was: $digest")
        assertTrue(digest.all { it in "0123456789abcdef" }, "digest was: $digest")
    }

    @Test
    fun signsForTheOriginTheRequestIsGoingTo() {




        val music = Innertube.sapisidHash("secret", "https://music.youtube.com")
        val youtube = Innertube.sapisidHash("secret", "https://www.youtube.com")
        assertNotNull(music)
        assertTrue(music != youtube, "signatures collided across origins")
    }
}
