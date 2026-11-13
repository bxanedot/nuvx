package com.bxane.nuvx.wear

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import com.bxane.nuvx.wear.WearProtocol as P

object WatchInstaller {

    private const val TAG = "NuvxWear"
    private const val APK_ASSET = "wear/NuvxWear.apk"
    private const val SHA_ASSET = "wear/NuvxWear.sha256"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _watches = MutableStateFlow<List<WatchEntry>?>(null)

    val watches: StateFlow<List<WatchEntry>?> = _watches.asStateFlow()

    private val _update = MutableStateFlow<WatchUpdateState>(WatchUpdateState.Idle)
    val update: StateFlow<WatchUpdateState> = _update.asStateFlow()

    private val _pairing = MutableStateFlow<WatchPairingState>(WatchPairingState.Idle)
    val pairing: StateFlow<WatchPairingState> = _pairing.asStateFlow()

    fun available(context: Context): Boolean = bundledHash(context) != null

    private var cachedHash: String? = null

    private fun bundledHash(context: Context): String? = cachedHash ?: runCatching {
        context.assets.open(SHA_ASSET).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.also { cachedHash = it }

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val nodes = runCatching {
                Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS)
            }.getOrElse {
                _watches.value = emptyList()
                return@launch
            }
            val known = _watches.value.orEmpty().associateBy { it.nodeId }
            _watches.value = nodes.map { node ->
                WatchEntry(
                    nodeId = node.id,
                    name = node.displayName,
                    status = known[node.id]?.status?.takeIf { it != WatchAppStatus.NOT_INSTALLED }
                        ?: WatchAppStatus.CHECKING,
                    versionName = known[node.id]?.versionName,
                )
            }
            nodes.forEach { node -> launch { ask(app, node.id) } }
        }
    }

    private suspend fun ask(app: Context, nodeId: String) {
        val askedAt = System.currentTimeMillis()
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(app).sendMessage(nodeId, P.PATH_WATCH_INFO_REQUEST, ByteArray(0)),
                10, TimeUnit.SECONDS,
            )
        }
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(nodeId).path(P.PATH_WATCH_INFO).build()
        val answer = withTimeoutOrNull(12_000) {
            while (true) {
                val map = runCatching {
                    val items = Tasks.await(Wearable.getDataClient(app).getDataItems(uri), 5, TimeUnit.SECONDS)
                    val found = items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
                    items.release()
                    found
                }.getOrNull()
                if (map != null && map.getLong(P.KEY_POSITION_AT) >= askedAt - 5_000) return@withTimeoutOrNull map
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (answer == null && System.currentTimeMillis() - (installedAt[nodeId] ?: 0L) < SETTLE_MS) return
        val status = when {
            answer == null -> WatchAppStatus.NOT_INSTALLED
            answer.getString(P.KEY_APK_SHA256) == bundledHash(app) -> WatchAppStatus.UP_TO_DATE
            else -> WatchAppStatus.UPDATE_AVAILABLE
        }
        _watches.update { list ->
            list?.map {
                if (it.nodeId != nodeId) it else it.copy(status = status, versionName = answer?.getString(P.KEY_VERSION_NAME))
            }
        }
    }

    private val installedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SETTLE_MS = 60_000L

    fun sendUpdate(context: Context, nodeId: String) {
        val app = context.applicationContext
        if (_update.value is WatchUpdateState.Sending) return
        _update.value = WatchUpdateState.Sending(nodeId)
        scope.launch {
            val result = runCatching {
                val apk = bundledApk(app)
                val client = Wearable.getChannelClient(app)
                val channel = Tasks.await(client.openChannel(nodeId, P.PATH_APK), 20, TimeUnit.SECONDS)
                val closed = CompletableDeferred<Int>()
                val callback = object : ChannelClient.ChannelCallback() {
                    override fun onOutputClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                        if (c.path == channel.path) closed.complete(closeReason)
                    }
                }
                Tasks.await(client.registerChannelCallback(channel, callback), 10, TimeUnit.SECONDS)
                Tasks.await(client.sendFile(channel, Uri.fromFile(apk)), 20, TimeUnit.SECONDS)
                val reason = withTimeoutOrNull(5 * 60_000) { closed.await() }
                runCatching { client.unregisterChannelCallback(channel, callback) }
                when (reason) {
                    ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL -> Unit
                    null, ChannelClient.ChannelCallback.CLOSE_REASON_CONNECTION_TIMEOUT -> error("the watch didn’t answer")
                    ChannelClient.ChannelCallback.CLOSE_REASON_DISCONNECTED -> error("the watch disconnected")
                    else -> error("the transfer stopped ($reason)")
                }
            }
            if (result.isSuccess) openOnWatch(app, nodeId)
            _update.value = result.fold(
                onSuccess = { WatchUpdateState.Sent(nodeId) },
                onFailure = {
                    Log.w(TAG, "watch update failed", it)
                    WatchUpdateState.Failed(nodeId, it.message ?: it.javaClass.simpleName)
                },
            )
            delay(20_000)
            refresh(app)
        }
    }

    private fun openOnWatch(app: Context, nodeId: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("nuvx-wear://${app.packageName}/update"))
        runCatching { RemoteActivityHelper(app).startRemoteActivity(intent, nodeId).get(15, TimeUnit.SECONDS) }
            .onFailure { Log.w(TAG, "couldn't open the watch app: ${it.message}") }
    }

    private fun bundledApk(app: Context): File {
        val target = File(app.cacheDir, "wear/NuvxWear.apk")
        val hash = bundledHash(app)
        val stamp = File(target.parentFile, "NuvxWear.sha256")
        if (target.exists() && stamp.exists() && stamp.readText() == hash) return target
        target.parentFile?.mkdirs()
        app.assets.open(APK_ASSET).use { input -> target.outputStream().use { input.copyTo(it) } }
        stamp.writeText(hash.orEmpty())
        return target
    }

    private var nsd: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    @Volatile private var pairingHost: String? = null
    @Volatile private var pairingPort: Int = 0

    private val connectPorts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun startPairing(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _pairing.value = WatchPairingState.Failed("unsupported")
            return
        }
        stopPairing()
        _pairing.value = WatchPairingState.Searching
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        discover(manager, PAIRING_SERVICE) { host, port ->
            pairingHost = host
            pairingPort = port
            if (_pairing.value is WatchPairingState.Searching) _pairing.value = WatchPairingState.Found(host)
        }
        discover(manager, CONNECT_SERVICE) { host, port -> connectPorts[host] = port }
    }

    fun stopPairing() {
        nsd?.let { manager -> listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } } }
        listeners.clear()
        nsd = null
        if (_pairing.value !is WatchPairingState.Installed) _pairing.value = WatchPairingState.Idle
    }

    fun pairAndInstall(context: Context, nodeId: String, code: String) {
        val app = context.applicationContext
        val host = pairingHost ?: return
        val port = pairingPort
        _pairing.value = WatchPairingState.Pairing
        scope.launch {
            val manager = runCatching { Adb(app) }.getOrElse {
                _pairing.value = WatchPairingState.Failed(it.message ?: "adb")
                return@launch
            }
            try {
                if (!manager.pair(host, port, code.trim())) error("pairing refused")
                val connectPort = withTimeoutOrNull(15_000) {
                    while (connectPorts[host] == null) delay(250)
                    connectPorts[host]
                } ?: error("watch not found after pairing")
                if (!manager.connect(host, connectPort)) error("connection refused")
                _pairing.value = WatchPairingState.Installing
                val apk = bundledApk(app)
                val output = manager.openStream("exec:cmd package install -r -S ${apk.length()}").use { stream ->
                    val out = stream.openOutputStream()
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    out.flush()
                    val reply = withTimeoutOrNull(120_000) {
                        runInterruptible { readInstallerReply(stream.openInputStream()) }
                    }
                    reply ?: error("the watch didn’t answer")
                }
                Log.i(TAG, "watch install: ${output.trim()}")
                if (!output.contains("Success")) error(output.trim().ifEmpty { "install failed" })
                runCatching {
                    manager.openStream("exec:am start -n ${app.packageName}/com.bxane.nuvx.wear.WearActivity").use { stream ->
                        withTimeoutOrNull(10_000) { runInterruptible { stream.openInputStream().read(ByteArray(256)) } }
                    }
                }
                installedAt[nodeId] = System.currentTimeMillis()
                _watches.update { list ->
                    list?.map { if (it.nodeId == nodeId) it.copy(status = WatchAppStatus.UP_TO_DATE) else it }
                }
                _pairing.value = WatchPairingState.Installed
                stopPairing()
                delay(5_000)
                refresh(app)
            } catch (e: Exception) {
                Log.w(TAG, "watch install failed", e)
                _pairing.value = WatchPairingState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { manager.disconnect() }
                runCatching { manager.close() }
            }
        }
    }

    private fun readInstallerReply(input: java.io.InputStream): String {
        val text = StringBuilder()
        val buffer = ByteArray(1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            text.append(String(buffer, 0, read, Charsets.UTF_8))
            if ("Success" in text || "Failure" in text) break
        }
        return text.toString()
    }

    private fun discover(manager: NsdManager, type: String, onFound: (String, Int) -> Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                manager.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = info.host?.hostAddress ?: return
                            onFound(host, info.port)
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listeners += listener
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private class Adb(app: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(30, TimeUnit.SECONDS)
            val dir = File(app.noBackupFilesDir, "adb").apply { mkdirs() }
            val keyFile = File(dir, "adbkey.pk8")
            val certFile = File(dir, "adbkey.crt")
            if (keyFile.exists() && certFile.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=nuvx")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now),
                    Date(now - 86_400_000L),
                    Date(now + 30L * 365 * 86_400_000L),
                    name,
                    pair.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                key = pair.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "nuvx"
    }
}
