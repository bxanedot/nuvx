package com.licht.nuvx.data.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * LAN Sync over Bluetooth: the same sealed requests as over Wi-Fi, for when the phone and the computer share no
 * network. It only carries bytes; pairing, approval, encryption and revocation are those of LAN Sync. The two
 * devices must already be paired in the phone's and computer's Bluetooth settings.
 */
@SuppressLint("MissingPermission") // every entry point checks [allowed] first
internal object LanBluetooth {
    /** Must match `SERVICE_UUID` in the desktop app's `lan_sync/bluetooth.rs`. */
    val SERVICE: UUID = UUID.fromString("6e757678-4c61-6e53-796e-630000000001")

    private val MAC = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun isAddress(text: String) = MAC.matches(text)

    /** Android 12+ needs the "Nearby devices" permission to open a Bluetooth connection. */
    fun allowed(): Boolean = this::app.isInitialized &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun adapter() = app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }

    /** Computers this phone is paired with in its Bluetooth settings, as (address, name). */
    fun pairedComputers(): List<Pair<String, String>> {
        if (!allowed()) return emptyList()
        return adapter()?.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
            .map { it.address to (it.name ?: it.address) }
    }

    private class Link(val address: String, val socket: BluetoothSocket) {
        val input: InputStream = BufferedInputStream(socket.inputStream)
        val output: OutputStream = socket.outputStream
    }

    private var link: Link? = null
    private val lock = Any()

    /** Sends one request over the kept-open connection to [address]; reconnects once if the old one went away. */
    fun exchange(address: String, request: ByteArray, read: (InputStream) -> Pair<Int, String>): Pair<Int, String> = synchronized(lock) {
        if (!allowed()) throw IOException("Bluetooth permission not granted")
        repeat(2) { attempt ->
            val current = link?.takeIf { it.address == address && it.socket.isConnected } ?: connect(address)
            try {
                current.output.write(request)
                current.output.flush()
                return read(current.input)
            } catch (e: IOException) {
                close()
                if (attempt == 1) throw e
            }
        }
        throw IOException("Bluetooth link failed")
    }

    private fun connect(address: String): Link {
        close()
        val adapter = adapter() ?: throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        // No cancelDiscovery(): it needs the scan permission, and nuvx never starts a discovery itself.
        val socket = device.createRfcommSocketToServiceRecord(SERVICE)
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("The computer is not reachable over Bluetooth", e)
        }
        return Link(address, socket).also { link = it }
    }

    fun close() = synchronized(lock) {
        link?.let { runCatching { it.socket.close() } }
        link = null
    }
}
