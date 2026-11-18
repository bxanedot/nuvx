package com.licht.nuvx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.licht.nuvx.data.sync.LanAddress
import com.licht.nuvx.data.sync.LanHost
import com.licht.nuvx.data.sync.LanPairingClient
import com.licht.nuvx.data.sync.LanSyncCrypto
import com.licht.nuvx.data.sync.LanSyncInterceptor
import com.licht.nuvx.data.sync.SyncResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A second phone pairing with this one as its host, over real sockets on this device: the same client code a
 * phone uses with the desktop app. Cleans up the device it adds.
 */
@RunWith(AndroidJUnit4::class)
class PhoneHostDeviceTest {
    @Test
    fun anotherPhonePairsSyncsAndIsLockedOutWhenRemoved() {
        LanHost.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val wasRunning = LanHost.state.value.running
        assertTrue(LanHost.start())
        val to = LanAddress("127.0.0.1", LanHost.state.value.port)
        var deviceId: String? = null
        try {
            val pairing = LanPairingClient.start(to, "Test phone").getOrThrow()
            assertEquals("not before approval", SyncResult.Waiting, LanPairingClient.claim(pairing))

            // Both screens show the same code: the host's pending request carries the one the joining phone shows.
            val pending = LanHost.state.value.pending.single { it.name == "Test phone" }
            assertEquals(pairing.code, pending.code)
            assertTrue(LanHost.approve(pending.id))

            val joined = LanPairingClient.claim(pairing) as SyncResult.Joined
            deviceId = joined.deviceId
            val key = LanSyncCrypto.unb64(joined.token)!!
            val http = OkHttpClient.Builder().addInterceptor(LanSyncInterceptor(to, joined.deviceId, key)).build()

            fun call(method: String, path: String, body: String? = null) = http.newCall(
                Request.Builder().url("http://lan.invalid$path").method(method, body?.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { it.code to it.body?.string().orEmpty() }

            val opId = "test-" + System.nanoTime()
            val push = """{"ops":[{"id":"$opId","kind":"playlist.create","entity":"pl-$opId","payload":{"title":"From the other phone"},"clientMs":1}]}"""
            assertEquals(200, call("POST", "/v1/ops", push).first)
            val (status, pulled) = call("GET", "/v1/ops?after=0")
            assertEquals(200, status)
            val ops = Json.parseToJsonElement(pulled).jsonObject["ops"]!!.jsonArray
            assertTrue("the pushed op comes back", ops.any { it.jsonObject["id"]?.jsonPrimitive?.content == opId })
            assertEquals("pushing it again keeps one copy", 200, call("POST", "/v1/ops", push).first)
            assertEquals(1, Json.parseToJsonElement(call("GET", "/v1/ops?after=0").second).jsonObject["ops"]!!.jsonArray.count { it.jsonObject["id"]?.jsonPrimitive?.content == opId })

            // A wrong key is refused outright.
            val stranger = OkHttpClient.Builder().addInterceptor(LanSyncInterceptor(to, joined.deviceId, LanSyncCrypto.randomBytes(32))).build()
            assertEquals(401, stranger.newCall(Request.Builder().url("http://lan.invalid/v1/ops?after=0").get().build()).execute().use { it.code })

            // Removed on this phone: the other phone is locked out.
            assertTrue(LanHost.revoke(joined.deviceId))
            deviceId = null
            assertEquals(401, call("GET", "/v1/ops?after=0").first)
        } finally {
            deviceId?.let(LanHost::revoke)
            if (!wasRunning) LanHost.stop()
        }
        assertNotNull(to)
    }
}
