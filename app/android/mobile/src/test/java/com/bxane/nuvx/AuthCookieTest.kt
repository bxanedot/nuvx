package com.bxane.nuvx

import com.bxane.nuvx.auth.AuthStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCookieTest {

    @Test
    fun `plain SAPISID is accepted`() {
        assertTrue(AuthStore.hasApiSid("SID=abc; SAPISID=secret; HSID=def"))
    }

    @Test
    fun `a jar with only the secure forms is accepted`() {
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-3PAPISID=secret"))
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-1PAPISID=secret"))
    }

    @Test
    fun `leading and trailing whitespace does not hide a cookie`() {
        assertTrue(AuthStore.hasApiSid("SID=abc;SAPISID=secret;HSID=def"))
        assertTrue(AuthStore.hasApiSid("  SAPISID = secret  "))
    }

    @Test
    fun `a jar with no signing secret is rejected`() {
        assertFalse(AuthStore.hasApiSid("SID=abc; HSID=def; SSID=ghi; APISID=jkl"))
        assertFalse(AuthStore.hasApiSid(""))
    }

    @Test
    fun `a present but empty value is rejected`() {
        assertFalse(AuthStore.hasApiSid("SID=abc; SAPISID=; HSID=def"))
    }

    @Test
    fun `the name has to be the whole name`() {
        assertFalse(AuthStore.hasApiSid("APISID=secret"))
        assertFalse(AuthStore.hasApiSid("NOT-SAPISID=secret"))
        assertFalse(AuthStore.hasApiSid("PREF=tz=SAPISID"))
    }
}
