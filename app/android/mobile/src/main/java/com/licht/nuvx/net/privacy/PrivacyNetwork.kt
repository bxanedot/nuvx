package com.licht.nuvx.net.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ProxySelector

/**
 * Privacy Connection for this process. The mode is read once at start-up and applies until the app restarts:
 * OkHttp keeps open connections alive, so switching a running process between direct and Tor could reuse a
 * direct connection for a protected request.
 */
object PrivacyNetwork {
    private const val PREFS = "privacy_connection"
    private const val KEY_MODE = "mode"
    private const val KEY_LOCAL_SOCKS_PORT = "local_socks_port"
    private const val DEFAULT_LOCAL_SOCKS_PORT = 10808

    /** Built-in Tor + Snowflake is not bundled; Orbot and external local SOCKS5 are supported. */
    const val EMBEDDED_AVAILABLE = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disabled = MutableStateFlow<ConnectionState>(ConnectionState.Disabled).asStateFlow()
    private val _savedMode = MutableStateFlow(PrivacyMode.OFF)
    private val _savedLocalSocksPort = MutableStateFlow(DEFAULT_LOCAL_SOCKS_PORT)
    private var manager: ConnectionManager? = null

    /** What the user chose; differs from [activeMode] until the next start. */
    val savedMode: StateFlow<PrivacyMode> = _savedMode.asStateFlow()
    val savedLocalSocksPort: StateFlow<Int> = _savedLocalSocksPort.asStateFlow()

    var activeLocalSocksPort: Int = DEFAULT_LOCAL_SOCKS_PORT
        private set

    var activeMode: PrivacyMode = PrivacyMode.OFF
        private set

    /** False when this device's WebView can't be given a proxy: sign-in pages would then go out directly. */
    var webViewCovered: Boolean = true
        private set

    val state: StateFlow<ConnectionState> get() = manager?.state ?: disabled

    /** Must run before the first network client is created, so every client picks up the selector. */
    fun install(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MODE, null)
        activeLocalSocksPort = prefs.getInt(KEY_LOCAL_SOCKS_PORT, DEFAULT_LOCAL_SOCKS_PORT)
            .takeIf { it in 1..65535 } ?: DEFAULT_LOCAL_SOCKS_PORT
        _savedLocalSocksPort.value = activeLocalSocksPort
        val mode = PrivacyMode.entries.firstOrNull { it.name == stored } ?: PrivacyMode.OFF
        _savedMode.value = mode
        activeMode = mode
        if (mode == PrivacyMode.OFF) return

        val backends = listOf<ConnectionBackend>(OrbotBackend(app), LocalSocksBackend(activeLocalSocksPort))
        val m = ConnectionManager(mode, backends, scope)
        manager = m
        ProxySelector.setDefault(
            PrivacyProxySelector(protected = true, socksPort = { m.socksPort }, onConnectFailed = m::reportTransportFailure),
        )
        routeWebViews(m)
        m.start()
        retryWhenTheNetworkReturns(app, m)
    }

    fun setMode(context: Context, mode: PrivacyMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        _savedMode.value = mode
    }

    fun setLocalSocksPort(context: Context, port: Int) {
        require(port in 1..65535) { "SOCKS port must be between 1 and 65535" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LOCAL_SOCKS_PORT, port).apply()
        _savedLocalSocksPort.value = port
    }

    fun retry() {
        manager?.retry()
    }

    /** A real request through the current connection; false when nothing is connected. */
    suspend fun test(): Boolean = manager?.verifyCurrent() ?: false

    private fun routeWebViews(m: ConnectionManager) {
        webViewCovered = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)
        if (!webViewCovered) return
        scope.launch {
            m.state.collect { state ->
                // No direct fallback rule: with nothing connected, WebViews point at a closed port and fail.
                val port = (state as? ConnectionState.Connected)?.socksPort ?: 9
                val config = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build()
                withContext(Dispatchers.Main) {
                    runCatching { ProxyController.getInstance().setProxyOverride(config, Runnable::run) {} }
                }
            }
        }
    }

    private fun retryWhenTheNetworkReturns(context: Context, m: ConnectionManager) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (m.state.value is ConnectionState.Unavailable) m.retry()
                }
            })
        }
    }
}
