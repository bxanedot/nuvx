package com.licht.nuvx.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

class LanSyncCryptoTest {
    // The fixed vector from `app/desktop/src/lan_sync/crypto.rs` (phone scalar 0x07…, host scalar 0x09…).
    private val phonePub = "BB4YUy_UdUwC8wQdnHXOszuD_9gax85P6ILMscmLxYlupGwxHE4v9A3ZajZT5uRURdMt_khuztdcepDGoYiBwKM"
    private val hostPub = "BHE1-k_ZOgnc6Yu_aBtL_PUOfA1jVOYq-wv_KjQpYXhl7UwfAt25Aj7lalV-UV1qncZsEfIglg3llDNN9Yh3ZyQ"
    private val key = "He1TxIRwj2hWEvYnwAJYQyqJdgo8rKrN1Ezb8NRta7Q"
    private val sealedByRust = "sFc71FAE_1Q5TiyOGxED8boUNH-i60-K8vqRAr87cNNmB8WEMn1EwosJXA"

    private fun phoneKeyPair(): KeyPair {
        val params = (LanSyncCrypto.newKeyPair().public as ECPublicKey).params
        val pub = LanSyncCrypto.unb64(phonePub)!!
        val point = ECPoint(BigInteger(1, pub.copyOfRange(1, 33)), BigInteger(1, pub.copyOfRange(33, 65)))
        val kf = KeyFactory.getInstance("EC")
        return KeyPair(kf.generatePublic(ECPublicKeySpec(point, params)), kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, ByteArray(32) { 7 }), params)))
    }

    @Test
    fun `the phone derives the same key and code as the computer`() {
        val phone = phoneKeyPair()
        assertEquals(phonePub, LanSyncCrypto.b64(LanSyncCrypto.encode(phone.public as ECPublicKey)))
        val (derived, code) = LanSyncCrypto.agree(phone, LanSyncCrypto.unb64(hostPub)!!)!!
        assertEquals(key, LanSyncCrypto.b64(derived))
        assertEquals("954760", code)
        assertNull(LanSyncCrypto.agree(phone, ByteArray(65)), "not a point on the curve")
    }

    @Test
    fun `envelopes cross between Rust and Kotlin and nothing else opens them`() {
        val k = LanSyncCrypto.unb64(key)!!
        assertEquals("hello from rust", LanSyncCrypto.open(k, "dev1".toByteArray(), sealedByRust)?.decodeToString())
        assertNull(LanSyncCrypto.open(k, "dev2".toByteArray(), sealedByRust))
        assertNull(LanSyncCrypto.open(ByteArray(32), "dev1".toByteArray(), sealedByRust))
        val sealed = LanSyncCrypto.seal(k, "dev1".toByteArray(), "hello from kotlin".toByteArray())
        println("sealed_by_kotlin=$sealed")
        assertArrayEquals("hello from kotlin".toByteArray(), LanSyncCrypto.open(k, "dev1".toByteArray(), sealed))
    }

    @Test
    fun `addresses parse and only this network counts as local`() {
        assertEquals(LanAddress("192.168.1.20", 47731), LanAddress.parse("192.168.1.20"))
        assertEquals(LanAddress("192.168.1.20", 5000), LanAddress.parse(" http://192.168.1.20:5000/ "))
        assertEquals(LanAddress("fe80::1", 47731), LanAddress.parse("[fe80::1]"))
        assertNull(LanAddress.parse("192.168.1.20:99999"))
        assertNull(LanAddress.parse("user@host"))
        assertNull(LanAddress.parse(""))
        for (ok in listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "fe80::1", "fd00::5")) {
            assertTrue(ok, LanAddress.isLocal(InetAddress.getByName(ok)))
        }
        for (bad in listOf("8.8.8.8", "1.1.1.1", "2001:4860::8888")) {
            assertFalse(bad, LanAddress.isLocal(InetAddress.getByName(bad)))
        }
    }

    private fun assertNull(value: Any?, message: String) = org.junit.Assert.assertNull(message, value)
}
