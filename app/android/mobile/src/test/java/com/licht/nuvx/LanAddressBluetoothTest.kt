package com.licht.nuvx

import com.licht.nuvx.data.sync.LanAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressBluetoothTest {
    @Test
    fun bluetoothAddressesRoundTripAndWiFiStaysAsItWas() {
        val bt = LanAddress.parse("bt/a1:b2:c3:d4:e5:f6")!!
        assertTrue(bt.bluetooth)
        assertEquals("A1:B2:C3:D4:E5:F6", bt.host)
        assertEquals("bt/A1:B2:C3:D4:E5:F6", bt.toString())
        assertEquals(bt, LanAddress.parse(bt.toString()))

        assertNull(LanAddress.parse("bt/not-a-mac"))
        assertNull(LanAddress.parse("bt/A1:B2:C3:D4:E5"))

        val wifi = LanAddress.parse("192.168.1.106:47731")!!
        assertFalse(wifi.bluetooth)
        assertEquals("192.168.1.106:47731", wifi.toString())
    }
}
