package com.licht.nuvx.net.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Asks the Tor Project's own check service, through the given SOCKS port, whether the request left through Tor. */
internal object TorCheck {
    private const val URL = "https://check.torproject.org/api/ip"

    suspend fun passes(socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), socksPort))
        val client = OkHttpClient.Builder().proxy(proxy)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                response.isSuccessful && response.body.string().replace(" ", "").contains("\"IsTor\":true")
            }
        } catch (_: java.io.IOException) {
            false
        } finally {
            client.connectionPool.evictAll()
        }
    }
}

/**
 * Uses the Orbot app's Tor through its local SOCKS port. Orbot is only asked through its public broadcast
 * interface; nuvx never changes Orbot's settings and never stops it.
 */
class OrbotBackend(private val context: Context) : ConnectionBackend {
    override val id = BackendId.ORBOT

    override suspend fun connect(onProgress: (Int) -> Unit): Int? {
        if (!isInstalled(context)) return null
        // Orbot answers ACTION_START with its current status and port, then broadcasts every change.
        // Nothing in Orbot answers a status request on its own, so START is also how we ask.
        val statuses = Channel<OrbotStatus>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, -1).takeIf { it in 1..65535 }
                statuses.trySend(OrbotStatus(intent.getStringExtra(EXTRA_STATUS) == STATUS_ON, port))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_EXPORTED)
        return try {
            context.sendBroadcast(Intent(ACTION_START).setPackage(PACKAGE).putExtra(EXTRA_PACKAGE_NAME, context.packageName))
            awaitOrbot(statuses, STATUS_WAIT_MS, START_WAIT_MS, onProgress, TorCheck::passes)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override suspend fun verify(socksPort: Int): Boolean = TorCheck.passes(socksPort)

    override suspend fun stop() = Unit

    companion object {
        const val PACKAGE = "org.torproject.android"
        private const val ACTION_START = "org.torproject.android.intent.action.START"
        private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
        private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
        private const val EXTRA_SOCKS_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
        private const val STATUS_ON = "ON"
        const val DEFAULT_SOCKS_PORT = 9050
        private const val STATUS_WAIT_MS = 4_000L
        private const val START_WAIT_MS = 45_000L

        fun isInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

        /** Opens Orbot if it is installed, otherwise its official install page. */
        fun open(context: Context) {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://orbot.app/download"))
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

internal class OrbotStatus(val on: Boolean, val socksPort: Int?)

/**
 * The port to use once Orbot says Tor is on and a real request went through it. If Orbot does not answer at all
 * (its "allow background starts" is off) Tor may still be running, so its default port is tried directly.
 */
internal suspend fun awaitOrbot(
    statuses: ReceiveChannel<OrbotStatus>,
    replyWaitMs: Long,
    startWaitMs: Long,
    onProgress: (Int) -> Unit,
    passes: suspend (Int) -> Boolean,
): Int? {
    val first = withTimeoutOrNull(replyWaitMs) { statuses.receive() }
        ?: return OrbotBackend.DEFAULT_SOCKS_PORT.takeIf { passes(it) }
    val on = if (first.on) first else withTimeoutOrNull(startWaitMs) {
        var latest = statuses.receive()
        while (!latest.on) latest = statuses.receive()
        latest
    } ?: return null
    onProgress(100)
    // A status change may omit the port; the first reply carries it.
    val port = on.socksPort ?: first.socksPort ?: OrbotBackend.DEFAULT_SOCKS_PORT
    return port.takeIf { passes(it) }
}
