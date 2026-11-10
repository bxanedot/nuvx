package com.bxane.nuvx.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bxane.nuvx.R
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

object PlaylistSync {

    private const val TAG = "nuvx"
    private const val PERIODIC_WORK = "playlist_sync"
    private const val ONE_OFF_WORK = "playlist_sync_now"
    private const val PREFS = "nuvx_playlist_sync"
    private const val KEY_LAST_CHECK = "last_complete_check"
    private const val CHANNEL_ID = "playlist_updates"
    internal const val KEY_APPROVED = "approved"

    private const val PERIOD_HOURS = 12L
    private const val FLEX_HOURS = 4L

    private const val CATCH_UP_MILLIS = 6L * 60 * 60 * 1000

    internal const val ASK_ABOVE = 50

    private const val PAUSE_MILLIS = 400L

    private val running = Mutex()

    fun init(app: Application) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var wasOn: Boolean? = null
            AppSettings.syncDownloadedPlaylists
                .combine(AppSettings.wifiOnlyDownloads) { on, wifiOnly -> on to wifiOnly }
                .distinctUntilChanged()
                .collect { (on, wifiOnly) ->
                    val firstLook = wasOn == null
                    if (on) {
                        schedule(app, wifiOnly, replace = !firstLook)
                        if (wasOn == false) checkNow(app)
                    } else if (!firstLook) {
                        cancel(app)
                    }
                    wasOn = on
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (AppSettings.syncDownloadedPlaylists.value && catchUpDue(app)) checkNow(app)
            }
        })
    }

    fun checkNow(context: Context, approved: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setConstraints(constraints(AppSettings.wifiOnlyDownloads.value))
            .setInputData(workDataOf(KEY_APPROVED to approved))
            .build()
        val policy = if (approved) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF_WORK, policy, request)
    }

    internal fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(ONE_OFF_WORK)
    }

    private fun schedule(context: Context, wifiOnly: Boolean, replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            PERIOD_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints(wifiOnly)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    private fun catchUpDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) >= CATCH_UP_MILLIS

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal suspend fun check(context: Context, approved: Boolean) {
        if (approved) running.lock() else if (!running.tryLock()) return
        try {
            if (!storageWritable(context)) return
            var whole = true
            Downloads.collections.value.keys.forEach { id ->
                if (!AppSettings.syncDownloadedPlaylists.value || !AppSettings.downloadsAllowedNow) return
                val record = Downloads.collections.value[id] ?: return@forEach
                val browseId = syncableBrowseId(record) ?: return@forEach
                val listing = readWholePlaylist(
                    first = { YtMusicRepository.browseSongs(browseId) },
                    more = { YtMusicRepository.moreSongs(it) },
                )
                if (!listing.complete) whole = false
                update(context, record, listing, approved)
                delay(PAUSE_MILLIS)
            }
            if (whole) prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } finally {
            running.unlock()
        }
    }

    private suspend fun update(context: Context, record: SavedCollection, listing: Listing, approved: Boolean) {
        val fresh = newTracks(listing.songs, record)
        val notification = record.id.hashCode()
        if (fresh.isEmpty()) {
            notifications(context)?.cancel(notification)
            return
        }
        if (fresh.size > ASK_ABOVE && !approved) {
            post(context, notification, askNotification(context, notification, record.title, fresh.size))
            return
        }
        val saved = Downloads.saved.value
        val (onDevice, missing) = fresh.partition { it.videoId in saved }
        val cover = listing.cover ?: record.thumbnailUrl?.takeIf { it.startsWith("http") }
        val fetch = missing.map { song ->
            if (song.thumbnailUrl.isNullOrBlank() && cover != null) song.copy(thumbnailUrl = cover) else song
        }
        val handled = onDevice.mapTo(HashSet()) { it.videoId } + Downloads.downloadNow(context, fetch, record.title)

        val current = Downloads.collections.value[record.id] ?: return
        val order = listingToRecord(listing.songs, current, handled)
        Downloads.rememberCollection(
            DownloadTarget(
                id = current.id,
                title = current.title,
                subtitle = current.subtitle,
                thumbnailUrl = current.thumbnailUrl,
                playlist = true,
            ),
            order,
        )
        val arrived = missing.count { it.videoId in handled && it.videoId in Downloads.saved.value }
        if (arrived > 0) post(context, notification, doneNotification(context, record.title, arrived))
    }

    private fun storageWritable(context: Context): Boolean =
        !AppSettings.exportDownloads.value || !DownloadStore.needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    internal class Listing(val songs: List<Song>, val complete: Boolean, val cover: String? = null)

    internal suspend fun readWholePlaylist(
        first: suspend () -> Result<YtMusicRepository.SongPage>,
        more: suspend (String) -> Result<YtMusicRepository.SongPage>,
    ): Listing {
        val opening = first().getOrNull() ?: return Listing(emptyList(), complete = false)
        val cover = opening.header?.thumbnailUrl
        val songs = LinkedHashMap<String, Song>()
        opening.songs.forEach { songs.putIfAbsent(it.videoId, it) }
        var next = opening.continuation
        while (next != null) {
            delay(PAUSE_MILLIS)
            val page = more(next).getOrNull() ?: return Listing(songs.values.toList(), complete = false, cover)
            val before = songs.size
            page.songs.forEach { songs.putIfAbsent(it.videoId, it) }
            if (songs.size == before) break
            next = page.continuation
        }
        return Listing(songs.values.toList(), complete = true, cover)
    }

    internal fun syncableBrowseId(record: SavedCollection): String? {
        val id = record.id
        return when {
            !record.playlist || ':' in id || id.startsWith("MP") -> null
            id.startsWith("VL") -> id
            else -> "VL$id"
        }
    }

    internal fun newTracks(listing: List<Song>, record: SavedCollection): List<Song> {
        val known = record.videoIds.toHashSet()
        return listing.filter { it.videoId !in known }
    }

    internal fun listingToRecord(listing: List<Song>, record: SavedCollection, handled: Set<String>): List<Song> {
        val known = record.videoIds.toHashSet()
        return listing.filter { it.videoId in known || it.videoId in handled }
    }

    private fun notifications(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = notifications(context) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playlist_updates_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "playlist update not posted: ${it.message}") }
    }

    private fun askNotification(context: Context, id: Int, playlist: String, count: Int): Notification {
        val approve = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, PlaylistSyncApproval::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(context)
            .setContentTitle(context.getString(R.string.playlist_sync_ask_title, playlist, count))
            .setContentText(context.getString(R.string.playlist_sync_ask_text))
            .setContentIntent(approve)
            .build()
    }

    private fun doneNotification(context: Context, playlist: String, count: Int): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return builder(context)
            .setContentTitle(context.getString(R.string.playlist_sync_done_title, playlist, count))
            .setContentIntent(open)
            .build()
    }

    private fun builder(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setAutoCancel(true)
}

class PlaylistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AppSettings.syncDownloadedPlaylists.value) {
            PlaylistSync.cancel(applicationContext)
            return Result.success()
        }
        PlaylistSync.check(applicationContext, inputData.getBoolean(PlaylistSync.KEY_APPROVED, false))
        return Result.success()
    }
}

class PlaylistSyncApproval : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaylistSync.checkNow(context, approved = true)
    }
}
