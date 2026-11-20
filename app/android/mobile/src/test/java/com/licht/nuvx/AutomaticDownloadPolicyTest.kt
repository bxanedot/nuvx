package com.licht.nuvx

import com.licht.nuvx.download.automaticDownloadAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticDownloadPolicyTest {
    @Test
    fun optInAndNetworkTransitionsRespectWifiAndMeteredPreferences() {
        fun may(on: Boolean, wifiOnly: Boolean, online: Boolean, wifi: Boolean, metered: Boolean) =
            automaticDownloadAllowed(on, wifiOnly, online, wifi, metered)

        assertFalse(may(false, false, true, true, false), "new automatic downloads default off")
        assertFalse(may(true, true, false, true, false), "disconnected")
        assertFalse(may(true, true, true, false, false), "unmetered cellular is not Wi-Fi")
        assertFalse(may(true, true, true, true, true), "metered Wi-Fi")
        assertTrue(may(true, true, true, true, false), "resumes on unmetered Wi-Fi")
        assertTrue(may(true, false, true, false, true), "user can explicitly allow metered downloads")
        assertFalse(may(true, false, false, false, true), "offline always blocks")
    }
}
