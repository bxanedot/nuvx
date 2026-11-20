package com.licht.nuvx.data.sync

import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndependentThemeSyncTest {
    private lateinit var server: SyncTestServer
    private val http = OkHttpClient()
    private class Store : SyncStore {
        var saved: String? = null
        override fun load(): String? = saved
        override fun save(state: String) { saved = state }
    }

    @BeforeTest fun start() { server = SyncTestServer() }
    @AfterTest fun stop() = server.close()

    @Test
    fun twoDevicesConvergeOnVersionOneThemeWithoutUploadingDeviceOnlyOrSecretSettings() {
        val owner = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).createSpace("Phone"))
        var enabled = false
        val store = Store()
        val phone = IndependentSyncChannel(
            SyncClient(server.url, owner.token, http), store, { enabled },
            category = IndependentCategory.THEME
        )
        assertFalse(phone.setThemeMode("DARK"))
        assertIs<IndependentResult.Disabled>(phone.sync())
        assertEquals(null, store.saved, "off means no local queued operation or transmission")
        enabled = true
        assertFalse(phone.setThemeMode("wifi_password"))
        assertFalse(phone.setThemeMode("EQ_DEVICE_12"))
        assertTrue(phone.setThemeMode("DARK"))
        assertIs<IndependentResult.Applied>(phone.sync())
        assertTrue(phone.current().pending.isEmpty())

        val pairing = assertIs<SyncResult.PairingStarted>(SyncClient(server.url, null, http).startPairing("Tablet"))
        assertIs<SyncResult.Approved>(SyncClient(server.url, owner.token, http).approve(pairing.code))
        val tablet = assertIs<SyncResult.Joined>(SyncClient(server.url, null, http).claim(pairing.code, pairing.secret))
        val other = IndependentSyncChannel(
            SyncClient(server.url, tablet.token, http), Store(), { true },
            category = IndependentCategory.THEME
        )
        assertEquals("DARK", assertIs<IndependentResult.Applied>(other.sync()).state.themeMode)
        assertTrue(other.setThemeMode("LIGHT"))
        assertIs<IndependentResult.Applied>(other.sync())
        assertEquals("LIGHT", assertIs<IndependentResult.Applied>(phone.sync()).state.themeMode)
        assertEquals("LIGHT", phone.current().themeMode)

        val receipt = assertIs<SyncResult.Pulled>(SyncClient(server.url, owner.token, http).pull(0))
        val settings = receipt.ops.filter { it.kind == "setting.set" }
        assertEquals(2, settings.size)
        assertTrue(settings.all { it.entity == "setting:theme_mode" })
        assertTrue(settings.all { it.payload.toString().contains("\"v\":1") })
        assertTrue(settings.none { it.payload.toString().contains("password") || it.payload.toString().contains("EQ_DEVICE") })
        enabled = false
        assertIs<IndependentResult.Disabled>(phone.sync())
    }
}
