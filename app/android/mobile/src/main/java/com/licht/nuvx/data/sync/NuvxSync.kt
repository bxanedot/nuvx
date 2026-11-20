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
import com.licht.nuvx.data.LikeState
import com.licht.nuvx.data.settings.AppSettings
import com.licht.nuvx.data.settings.ThemeMode
import com.licht.nuvx.data.model.LikeStatus
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylist
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    private const val KEY_LAN_HOST_ID = "lan_host_id"
    private const val KEY_LAN_HOST_NAME = "lan_host_name"
    private const val KEY_LAN_HOST_BT = "lan_host_bt"
    private const val KEY_HOSTING = "hosting"
    const val LAN_SCHEME = "lan://"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("nuvx_sync", Context.MODE_PRIVATE) }
    private val secure by lazy { EncryptedPrefs.open(app, "nuvx_sync_secure", "nuvx_sync_secure_plain") }
    private val running = Mutex()
    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()
    private val _syncFavorites = MutableStateFlow(false)
    val syncFavorites = _syncFavorites.asStateFlow()
    private val _syncHistory = MutableStateFlow(false)
    val syncHistory = _syncHistory.asStateFlow()
    private val _syncedHistory = MutableStateFlow<List<SyncedListen>>(emptyList())
    internal val syncedHistory = _syncedHistory.asStateFlow()
    private val _syncTheme = MutableStateFlow(false)
    val syncTheme = _syncTheme.asStateFlow()
    @Volatile private var applyingRemoteTheme = false

    val deviceName: String get() = listOf(Build.MANUFACTURER.replaceFirstChar(Char::uppercase), Build.MODEL).distinct().joinToString(" ").take(80)

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        LanBluetooth.init(app)
        LanHost.init(app)
        LanHost.onRemoteOps = ::requestSync
        _status.value = Status(
            enabled = prefs.getBoolean("enabled", false) && token() != null,
            server = prefs.getString("server", "").orEmpty(),
            deviceId = prefs.getString("device", "").orEmpty(),
            lastSyncMs = prefs.getLong("last", 0),
            error = prefs.getString("error", null),
        )
        _syncFavorites.value = prefs.getBoolean("sync_favorites", false)
        _syncHistory.value = prefs.getBoolean("sync_listening_history", false)
        _syncTheme.value = prefs.getBoolean("sync_theme_mode", false)
        LikeState.onChanged = { id, state -> recordLocalFavorite(id, state == LikeStatus.LIKE) }
        // Restore opted-in local states even when the app restarts completely offline.
        if (_syncFavorites.value) favoritesChannel().current().favorites.forEach { (id, liked) ->
            LikeState.applySynced(id, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
        }
        if (_syncHistory.value) _syncedHistory.value = historyChannel().current().history
        if (prefs.getBoolean(KEY_HOSTING, false)) startHosting()
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
    fun lanHttp(via: LanAddress? = null): okhttp3.OkHttpClient? {
        val status = _status.value
        if (!status.enabled) return null
        val lan = via ?: lanAddress(status.server) ?: return null
        val key = token()?.let(LanSyncCrypto::unb64) ?: return null
        return com.licht.nuvx.data.Http.client.newBuilder().addInterceptor(LanSyncInterceptor(lan, status.deviceId, key)).build()
    }

    // Local Wi-Fi Sync pairing: blocking network calls, run them on Dispatchers.IO.
    fun lanStart(address: LanAddress): Result<LanPairing> = LanPairingClient.start(address, deviceName)
    fun lanClaim(pairing: LanPairing): SyncResult =
        LanPairingClient.claim(pairing).also {
            if (it is SyncResult.Joined) {
                join("$LAN_SCHEME${pairing.to}", it)
                // Remembered so the phone can find this computer again by itself when its address changes.
                val hello = LanFinder.hello(pairing.to)
                prefs.edit()
                    .putString(KEY_LAN_HOST_ID, hello?.get("id")?.jsonPrimitive?.contentOrNull)
                    .putString(KEY_LAN_HOST_NAME, pairing.hostName)
                    .putString(KEY_LAN_HOST_BT, hello?.get("bt")?.jsonPrimitive?.contentOrNull?.takeIf(LanBluetooth::isAddress))
                    .apply()
            }
        }

    /** The paired computer's name, while this phone is paired over Local Wi-Fi Sync. */
    val lanHostName: String? get() = prefs.getString(KEY_LAN_HOST_NAME, null).takeIf { isLan && _status.value.enabled }

    /**
     * Automatic reconnect: when the paired computer no longer answers at its saved address (DHCP gave it a new
     * one), find it again on this Wi-Fi by its id and keep the new address. True when it is reachable.
     */
    suspend fun healLanAddress(): Boolean {
        val status = _status.value
        if (!status.enabled || !isLan) return false
        if (isHosting) return LanHost.state.value.running
        val saved = lanAddress(status.server) ?: return false
        val hostId = prefs.getString(KEY_LAN_HOST_ID, null)
        if (saved.bluetooth && hostId != null) {
            LanFinder.phoneAddress(app)?.let { LanFinder.findHost(it, hostId) }?.let { wifi ->
                val server = "$LAN_SCHEME$wifi"
                prefs.edit().putString("server", server).apply()
                _status.value = _status.value.copy(server = server, error = null)
                LanBluetooth.close()
                return true
            }
        }
        val here = kotlinx.coroutines.withContext(Dispatchers.IO) { LanFinder.hello(saved) }
        val hereId = here?.get("id")?.jsonPrimitive?.contentOrNull
        if (here != null && (hostId == null || hereId == hostId)) {
            // Learn (or refresh) how to find this computer later: older pairings saved neither its id nor Bluetooth.
            prefs.edit().apply {
                if (hostId == null && hereId != null) putString(KEY_LAN_HOST_ID, hereId)
                here["bt"]?.jsonPrimitive?.contentOrNull?.takeIf(LanBluetooth::isAddress)?.let { putString(KEY_LAN_HOST_BT, it) }
                here["name"]?.jsonPrimitive?.contentOrNull?.takeIf { prefs.getString(KEY_LAN_HOST_NAME, null) == null }?.let { putString(KEY_LAN_HOST_NAME, it) }
            }.apply()
            return true
        }
        if (hostId == null) return false
        val moved = LanFinder.phoneAddress(app)?.let { LanFinder.findHost(it, hostId) }
            // No Wi-Fi way to it (other network, no network): the same computer over Bluetooth, if it said where.
            ?: prefs.getString(KEY_LAN_HOST_BT, null)?.let { LanAddress(it, 0, bluetooth = true) }
                ?.takeIf { bt -> bt != saved && kotlinx.coroutines.withContext(Dispatchers.IO) { LanFinder.hello(bt) }?.get("id")?.jsonPrimitive?.contentOrNull == hostId }
            ?: return false
        val server = "$LAN_SCHEME$moved"
        prefs.edit().putString("server", server).apply()
        _status.value = _status.value.copy(server = server, error = null)
        DebugLog.d(TAG, "paired computer reached at $moved")
        return true
    }

    /** The paired computer's Bluetooth address, once it has told this phone. */
    val lanHostBluetooth: String? get() = prefs.getString(KEY_LAN_HOST_BT, null)

    /** True while the paired computer is reached over Bluetooth rather than Wi-Fi. */
    val viaBluetooth: Boolean get() = lanAddress(_status.value.server)?.bluetooth == true

    // Setup and pairing. Blocking network calls: run them on Dispatchers.IO.

    fun createSpace(server: String): SyncResult = client(server, null).createSpace(deviceName).also { if (it is SyncResult.Joined) join(server, it) }
    fun startPairing(server: String): SyncResult = client(server, null).startPairing(deviceName)
    fun claim(server: String, code: String, secret: String): SyncResult =
        client(server, null).claim(code, secret).also { if (it is SyncResult.Joined) join(server, it) }
    fun approve(code: String): SyncResult = client().approve(code)
    fun devices(): SyncResult = client().devices()
    fun revoke(deviceId: String): SyncResult = client().revoke(deviceId).also { result ->
        // Revoking this device invalidates its token immediately; do not wait for a worker's 401.
        if (result is SyncResult.Done && deviceId == _status.value.deviceId) {
            invalidateRevokedAuthority("This device was removed from sync.")
        }
    }
    /** Cloud Sync only: LAN peers use a separate pairing protocol. */
    fun revokeOthers(): SyncResult =
        if (isLan) SyncResult.Stop("This action is unavailable for local Wi-Fi Sync.") else client().revokeOthers()

    /** Leaves sync on this device. With [deleteServerData], first deletes the whole space on the server. */
    /** True while this phone hosts other phones (and syncs with itself over loopback). */
    val isHosting: Boolean get() = prefs.getBoolean(KEY_HOSTING, false)

    /**
     * Lets other phones pair with this one: starts the host and joins its own space over loopback, so this phone's
     * playlists sync with every phone that pairs. Replaces any other sync this phone had.
     */
    fun startHosting(): Boolean {
        if (!LanHost.start()) return false
        val (address, deviceId, key) = LanHost.selfJoin() ?: return false
        val server = "$LAN_SCHEME$address"
        prefs.edit().putBoolean(KEY_HOSTING, true).putString(KEY_LAN_HOST_NAME, deviceName).apply()
        if (_status.value.server != server || _status.value.deviceId != deviceId || !_status.value.enabled) {
            join(server, SyncResult.Joined(LanHost.spaceIdForSelf, deviceId, LanSyncCrypto.b64(key)))
        }
        return true
    }

    fun stopHosting() {
        LanHost.stop()
        if (isHosting) leave(deleteServerData = false)
    }

    fun leave(deleteServerData: Boolean): SyncResult {
        if (deleteServerData && token() != null) {
            val r = client().deleteSpace()
            if (r !is SyncResult.Done && r !is SyncResult.Stop) return r // keep the token so the user can retry
        }
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(ONE_OFF)
        if (isHosting) LanHost.stop() // turning sync off on the hosting phone stops hosting too
        secure.edit().remove(KEY_TOKEN).apply()
        prefs.edit().clear().apply()
        engine("").reset()
        _status.value = Status()
        return SyncResult.Done
    }

    private fun join(server: String, joined: SyncResult.Joined) {
        secure.edit().putString(KEY_TOKEN, joined.token).commit()
        engine("").reset() // a new space starts from an empty log
        favoritesChannel().resetForNewSpace()
        historyChannel().resetForNewSpace()
        themeChannel().resetForNewSpace()
        prefs.edit().putBoolean("enabled", true).putString("server", server).putString("device", joined.deviceId).remove("error").apply()
        _status.value = Status(enabled = true, server = server, deviceId = joined.deviceId)
        schedulePeriodic()
        requestSync()
    }

    /** Favorites are a separate, default-off opt-in from playlist Sync. */
    fun setSyncFavorites(enabled: Boolean) {
        _syncFavorites.value = enabled
        prefs.edit().putBoolean("sync_favorites", enabled).apply()
        if (enabled && _status.value.enabled) requestSync()
    }

    private fun favoritesChannel(): IndependentSyncChannel =
        IndependentSyncChannel(
            client(), FileStore(File(app.filesDir, "nuvx-sync-favorites.json")),
            enabled = { _status.value.enabled && _syncFavorites.value },
        )

    private fun recordLocalFavorite(videoId: String, liked: Boolean) {
        if (favoritesChannel().setFavorite(videoId, liked)) requestSync()
    }

    /** Off by default. Only counted plays of eligible 11-character video IDs may leave. */
    fun setSyncHistory(enabled: Boolean) {
        _syncHistory.value = enabled
        prefs.edit().putBoolean("sync_listening_history", enabled).apply()
        if (enabled && _status.value.enabled) requestSync()
    }

    private fun historyChannel(): IndependentSyncChannel =
        IndependentSyncChannel(
            client(), FileStore(File(app.filesDir, "nuvx-sync-history.json")),
            enabled = { _status.value.enabled && _syncHistory.value },
            category = IndependentCategory.HISTORY,
        )

    fun recordCountedPlay(videoId: String) {
        if (!_syncHistory.value || !_status.value.enabled) return
        if (historyChannel().recordPlay(videoId)) requestSync()
    }

    /** Opt-in only: the cross-device theme, not device performance or secure settings. */
    fun setSyncTheme(enabled: Boolean) {
        _syncTheme.value = enabled
        prefs.edit().putBoolean("sync_theme_mode", enabled).apply()
        if (enabled && _status.value.enabled) requestSync()
    }

    fun onThemeSelected(mode: ThemeMode) {
        if (applyingRemoteTheme || !_syncTheme.value || !_status.value.enabled) return
        if (themeChannel().setThemeMode(mode.name)) requestSync()
    }

    private fun themeChannel(): IndependentSyncChannel =
        IndependentSyncChannel(
            client(), FileStore(File(app.filesDir, "nuvx-sync-theme.json")),
            enabled = { _status.value.enabled && _syncTheme.value },
            category = IndependentCategory.THEME,
        )

    fun requestSync() {
        if (!_status.value.enabled) return // a revoked session must never schedule fresh work
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
        if (_status.value.enabled && isLan) healLanAddress()
        val s = _status.value
        if (!s.enabled) return SyncOutcome.Stop("Sync is off")
        _status.value = s.copy(syncing = true)
        val before = LocalPlaylistStore.playlists.value
        var outcome: SyncOutcome = runCatching {
            engine(s.server).syncOnce(before.map { LibraryPlaylist(it.id, it.title, it.songs) })
        }.getOrElse { SyncOutcome.RetryLater(it.javaClass.simpleName) }
        // A session may be revoked while the playlist request is in flight.
        // Do not start any optional data transfer using that obsolete authority.
        if (!_status.value.enabled || _status.value.server != s.server || _status.value.deviceId != s.deviceId) {
            return SyncOutcome.Stop("Sync authorization changed during request")
        }
        // Commit the playlist result before optional entity transfers. A favorites/history/theme
        // network failure must never make the next diff interpret remote playlists as local edits.
        if (outcome is SyncOutcome.Apply) {
            val applied = LocalPlaylistStore.replaceAllIfUnchanged(before, toLocal(outcome.playlists, before))
            if (!applied) requestSync()
        }
        if (outcome is SyncOutcome.Apply && _syncFavorites.value) {
            when (val extra = favoritesChannel().sync()) {
                is IndependentResult.Applied -> if (_syncFavorites.value) extra.state.favorites.forEach { (id, liked) ->
                    LikeState.applySynced(id, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
                }
                is IndependentResult.Failed -> outcome = when (val cause = extra.cause) {
                    is SyncResult.Stop -> SyncOutcome.Stop(cause.why, cause.authorizationLost)
                    is SyncResult.RetryLater -> SyncOutcome.RetryLater(cause.why)
                    else -> SyncOutcome.RetryLater("Favorites sync could not finish")
                }
                IndependentResult.Disabled -> Unit
            }
        }
        if (outcome is SyncOutcome.Apply && _syncHistory.value) {
            when (val extra = historyChannel().sync()) {
                is IndependentResult.Applied -> if (_syncHistory.value) _syncedHistory.value = extra.state.history
                is IndependentResult.Failed -> outcome = when (val cause = extra.cause) {
                    is SyncResult.Stop -> SyncOutcome.Stop(cause.why, cause.authorizationLost)
                    is SyncResult.RetryLater -> SyncOutcome.RetryLater(cause.why)
                    else -> SyncOutcome.RetryLater("Listening-history sync could not finish")
                }
                IndependentResult.Disabled -> Unit
            }
        }
        if (outcome is SyncOutcome.Apply && _syncTheme.value) {
            when (val extra = themeChannel().sync()) {
                is IndependentResult.Applied -> {
                    val mode = extra.state.themeMode?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    if (_syncTheme.value && mode != null && AppSettings.themeMode.value != mode) {
                        applyingRemoteTheme = true
                        try { AppSettings.setThemeMode(mode) } finally { applyingRemoteTheme = false }
                    }
                }
                is IndependentResult.Failed -> outcome = when (val cause = extra.cause) {
                    is SyncResult.Stop -> SyncOutcome.Stop(cause.why, cause.authorizationLost)
                    is SyncResult.RetryLater -> SyncOutcome.RetryLater(cause.why)
                    else -> SyncOutcome.RetryLater("Theme preference sync could not finish")
                }
                IndependentResult.Disabled -> Unit
            }
        }
        // A self-revocation can complete while this network call is in flight.
        // Do not apply its reply or re-enable a session that is no longer authorized.
        val stillAuthorized = _status.value.enabled &&
            _status.value.server == s.server && _status.value.deviceId == s.deviceId
        if (!stillAuthorized) return SyncOutcome.Stop("Sync authorization changed during request")
        when (outcome) {
            is SyncOutcome.Apply -> {
                prefs.edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
                _status.value = _status.value.copy(syncing = false, lastSyncMs = System.currentTimeMillis(), error = null)
            }
            is SyncOutcome.RetryLater -> {
                DebugLog.w(TAG, "sync will retry: ${outcome.why}")
                _status.value = _status.value.copy(syncing = false)
            }
            is SyncOutcome.Stop -> {
                if (outcome.authorizationLost) {
                    invalidateRevokedAuthority(outcome.why)
                } else {
                    prefs.edit().putString("error", outcome.why).apply()
                    _status.value = _status.value.copy(syncing = false, error = outcome.why)
                }
            }
        }
        outcome
    }

    /** Keep the Sync outbox and local playlists for explicit future re-pairing. */
    private fun invalidateRevokedAuthority(reason: String) {
        secure.edit().remove(KEY_TOKEN).commit()
        prefs.edit().putBoolean("enabled", false).putString("error", reason).commit()
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(ONE_OFF)
        _status.value = _status.value.copy(enabled = false, syncing = false, error = reason)
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
