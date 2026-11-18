package com.licht.nuvx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.licht.nuvx.data.sync.LanAddress
import com.licht.nuvx.data.sync.LanFinder
import com.licht.nuvx.data.sync.NuvxConnect
import com.licht.nuvx.data.sync.NuvxFiles
import com.licht.nuvx.data.sync.NuvxSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Talks to the paired nuvx computer over Bluetooth only. Runs on a device that is paired (LAN Sync and Bluetooth)
 * with a computer whose Bluetooth listener is up; skipped everywhere else (CI emulators have no paired computer).
 */
@RunWith(AndroidJUnit4::class)
class BluetoothLinkDeviceTest {
    @Test
    fun helloAndSealedRequestsTravelOverBluetooth() {
        val mac = NuvxSync.lanHostBluetooth
        assumeTrue("no computer paired over Bluetooth", mac != null && NuvxSync.status.value.enabled)
        val bt = LanAddress(mac!!, 0, bluetooth = true)

        val started = System.currentTimeMillis()
        val hello = LanFinder.hello(bt)
        assertNotNull("hello over Bluetooth", hello)
        android.util.Log.i("BtLinkTest", "hello in ${System.currentTimeMillis() - started} ms: ${hello!!["name"]}")

        // Sealed, authenticated requests: the same key and device id as over Wi-Fi.
        val http = NuvxSync.lanHttp(bt)
        val files = NuvxFiles.list(http).getOrThrow()
        android.util.Log.i("BtLinkTest", "download list over Bluetooth: ${files.size} songs")
        val state = NuvxConnect.state(http)
        android.util.Log.i("BtLinkTest", "connect state over Bluetooth: ${state.exceptionOrNull() ?: state.getOrNull()}")

        val small = files.minByOrNull { it.size }
        if (small != null) {
            val out = java.io.ByteArrayOutputStream()
            val t = System.currentTimeMillis()
            NuvxFiles.fetch(small, out, http = http)
            val ms = System.currentTimeMillis() - t
            assertEquals(small.size, out.size().toLong())
            android.util.Log.i("BtLinkTest", "copied ${small.size} bytes over Bluetooth in $ms ms")
        }
    }
}
