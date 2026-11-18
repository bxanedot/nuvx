package com.licht.nuvx.data.sync

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.licht.nuvx.auth.EncryptedPrefs
import com.licht.nuvx.data.DebugLog
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylist
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Nuvx Sync on Android: opt-in, off by default, nuvx playlists only (never YouTube Music data or sign-in).
 * The device token lives in encrypted preferences; the sync state in one atomically replaced file.
 */
object NuvxSync {
    data class Status(
        val enabled: Boolean = false,
        val server: String = "",
        val deviceId: String = "",
        val lastSyncMs: Long = 0,
        val syncing: Boolean = false,
        val error: String? = null,
    )

    private const val TAG = "NuvxSync"
    private const val PERIODIC = "nuvx-sync-periodic"
    private const val ONE_OFF = "nuvx-sync-now"
    private const val KEY_TOKEN = "token"
    const val LAN_SCHEME = "lan://"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("nuvx_sync", Context.MODE_PRIVATE) }
    private val secure by lazy { EncryptedPrefs.open(app, "nuvx_sync_secure", "nuvx_sync_secure_plain") }
    private val running = Mutex()
    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    val deviceName: String get() = listOf(Build.MANUFACTURER.replaceFirstChar(Char::uppercase), Build.MODEL).distinct().joinToString(" ").take(80)

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        _status.value = Status(
            enabled = prefs.getBoolean("enabled", false) && token() != null,
            server = prefs.getString("server", "").orEmpty(),
            deviceId = prefs.getString("device", "").orEmpty(),
            lastSyncMs = prefs.getLong("last", 0),
            error = prefs.getString("error", null),
        )
        if (_status.value.enabled) {
            schedulePeriodic()
            // A computer's edits can't reach the phone by themselves: catch up each time the app opens.
            if (isLan) requestSync()
        }
        // A playlist edit syncs a few seconds later instead of waiting for the periodic run.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            LocalPlaylistStore.playlists.drop(1).debounce(5_000).collect { if (_status.value.enabled) requestSync() }
        }
    }

    /** Sync servers must use HTTPS; plain HTTP only for this device or the emulator's host (self-hosting tests). */
    fun normalizeServer(input: String): String? {
        val text = input.trim().trimEnd('/')
        val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val local = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
        if (uri.scheme != "https" && !(uri.scheme == "http" && local)) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null) return null
        return uri.toString().trimEnd('/')
    }

    private fun token(): String? = secure.getString(KEY_TOKEN, null)

    /** Local Wi-Fi Sync keeps `lan://host:port` as its server; the secure token slot holds the device key. */
    private fun lanAddress(server: String): LanAddress? = server.removePrefix(LAN_SCHEME).takeIf { server.startsWith(LAN_SCHEME) }?.let(LanAddress::parse)

    private fun client(server: String = _status.value.server, token: String? = token()): SyncClient {
        val lan = lanAddress(server) ?: return SyncClient(server, token)
        val key = token?.let(LanSyncCrypto::unb64) ?: ByteArray(32)
        // The interceptor answers every call itself over the sealed LAN channel; this URL is never contacted.
        val http = com.licht.nuvx.data.Http.client.newBuilder().addInterceptor(LanSyncInterceptor(lan, _status.value.deviceId, key)).build()
        return SyncClient("http://lan.invalid", null, http)
    }

    val isLan: Boolean get() = _status.value.server.startsWith(LAN_SCHEME)

    /** The sealed LAN channel while this phone is paired with a computer; Nuvx Connect rides it too. */
    fun lanHttp(): okhttp3.OkHttpClient? {
        val status = _status.value
        if (!status.enabled) return null
        val lan = lanAddress(status.server) ?: return null
        val key = token()?.let(LanSyncCrypto::unb64) ?: return null
        return com.licht.nuvx.data.Http.client.newBuilder().addInterceptor(LanSyncInterceptor(lan, status.deviceId, key)).build()
    }

    // Local Wi-Fi Sync pairing: blocking network calls, run them on Dispatchers.IO.
    fun lanStart(address: LanAddress): Result<LanPairing> = LanPairingClient.start(address, deviceName)
    fun lanClaim(pairing: LanPairing): SyncResult =
        LanPairingClient.claim(pairing).also { if (it is SyncResult.Joined) join("$LAN_SCHEME${pairing.to}", it) }

    // Setup and pairing. Blocking network calls: run them on Dispatchers.IO.

    fun createSpace(server: String): SyncResult = client(server, null).createSpace(deviceName).also { if (it is SyncResult.Joined) join(server, it) }
    fun startPairing(server: String): SyncResult = client(server, null).startPairing(deviceName)
    fun claim(server: String, code: String, secret: String): SyncResult =
        client(server, null).claim(code, secret).also { if (it is SyncResult.Joined) join(server, it) }
    fun approve(code: String): SyncResult = client().approve(code)
    fun devices(): SyncResult = client().devices()
    fun revoke(deviceId: String): SyncResult = client().revoke(deviceId)

    /** Leaves sync on this device. With [deleteServerData], first deletes the whole space on the server. */
    fun leave(deleteServerData: Boolean): SyncResult {
        if (deleteServerData && token() != null) {
            val r = client().deleteSpace()
            if (r !is SyncResult.Done && r !is SyncResult.Stop) return r // keep the token so the user can retry
        }
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(ONE_OFF)
        secure.edit().remove(KEY_TOKEN).apply()
        prefs.edit().clear().apply()
        engine("").reset()
        _status.value = Status()
        return SyncResult.Done
    }

    private fun join(server: String, joined: SyncResult.Joined) {
        secure.edit().putString(KEY_TOKEN, joined.token).commit()
        engine("").reset() // a new space starts from an empty log
        prefs.edit().putBoolean("enabled", true).putString("server", server).putString("device", joined.deviceId).remove("error").apply()
        _status.value = Status(enabled = true, server = server, deviceId = joined.deviceId)
        schedulePeriodic()
        requestSync()
    }

    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<NuvxSyncWorker>()
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<NuvxSyncWorker>(1, TimeUnit.HOURS).setConstraints(online()).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun engine(server: String) = SyncEngine(client(server.ifEmpty { "https://unused.invalid" }), FileStore(File(app.filesDir, "nuvx-sync-state.json")))

    /** One sync run. Applies the result only if the playlists were not edited meanwhile. */
    suspend fun syncNow(): SyncOutcome = running.withLock {
        val s = _status.value
        if (!s.enabled) return SyncOutcome.Stop("Sync is off")
        _status.value = s.copy(syncing = true)
        val before = LocalPlaylistStore.playlists.value
        val outcome = runCatching {
            engine(s.server).syncOnce(before.map { LibraryPlaylist(it.id, it.title, it.songs) })
        }.getOrElse { SyncOutcome.RetryLater(it.javaClass.simpleName) }
        when (outcome) {
            is SyncOutcome.Apply -> {
                val applied = LocalPlaylistStore.replaceAllIfUnchanged(before, toLocal(outcome.playlists, before))
                if (!applied) requestSync() // edited during the run: go again with the new edits
                prefs.edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
                _status.value = _status.value.copy(syncing = false, lastSyncMs = System.currentTimeMillis(), error = null)
            }
            is SyncOutcome.RetryLater -> {
                DebugLog.w(TAG, "sync will retry: ${outcome.why}")
                _status.value = _status.value.copy(syncing = false)
            }
            is SyncOutcome.Stop -> {
                prefs.edit().putString("error", outcome.why).apply()
                _status.value = _status.value.copy(syncing = false, error = outcome.why)
            }
        }
        outcome
    }

    /** Keeps the richer local copy of a song (duration, album, artist ids) when this device already has it. */
    internal fun toLocal(synced: List<LibraryPlaylist>, current: List<LocalPlaylist>): List<LocalPlaylist> {
        val known = HashMap<String, Song>()
        current.forEach { p -> p.songs.forEach { known.putIfAbsent(it.videoId, it) } }
        return synced.map { p -> LocalPlaylist(p.id, p.title, p.songs.map { known[it.videoId] ?: it }) }
    }

    private class FileStore(file: File) : SyncStore {
        private val atomic = AtomicFile(file)
        override fun load(): String? = runCatching { atomic.readFully().decodeToString() }.getOrNull()
        override fun save(state: String) {
            val out = atomic.startWrite()
            try {
                out.write(state.encodeToByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }
    }
}

class NuvxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (NuvxSync.syncNow()) {
        is SyncOutcome.Apply -> Result.success()
        is SyncOutcome.RetryLater -> if (runAttemptCount < 8) Result.retry() else Result.failure()
        is SyncOutcome.Stop -> Result.failure()
    }
}
