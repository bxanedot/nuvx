package com.licht.nuvx.net.privacy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Connects Nuvx to a SOCKS5 listener on this device (for example, one started by a
 * separately installed Xray or Mihomo client). Nuvx does not install or control the tunnel.
 *
 * The port is only published to PrivacyProxySelector after an HTTPS request to YouTube
 * Music succeeds through SOCKS. A failed probe keeps the app's protected traffic blocked.
 */
internal class LocalSocksBackend(
    private val port: Int,
    private val probe: suspend (Int) -> Boolean = ::probeYouTubeMusicViaSocks,
) : ConnectionBackend {
    override val id: BackendId = BackendId.LOCAL_SOCKS

    override suspend fun connect(onProgress: (Int) -> Unit): Int? {
        if (port !in 1..65535) return null
        return if (probe(port)) port else null
    }

    override suspend fun verify(socksPort: Int): Boolean =
        socksPort == port && port in 1..65535 && probe(port)

    override suspend fun stop() = Unit
}

private suspend fun probeYouTubeMusicViaSocks(port: Int): Boolean = withContext(Dispatchers.IO) {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, port))
    val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    try {
        client.newCall(Request.Builder().url("https://music.youtube.com/").build()).execute().use {
            it.isSuccessful
        }
    } catch (_: IOException) {
        false
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
