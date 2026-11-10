package com.bxane.nuvx.wear.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.bxane.nuvx.wear.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.bxane.nuvx.wear.WearProtocol as P

object SelfUpdate {

    private const val TAG = "NuvxWear"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2

    fun publishInfo(context: Context) {
        val app = context.applicationContext
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return
        val request = PutDataMapRequest.create(P.PATH_WATCH_INFO).apply {
            dataMap.putLong(P.KEY_VERSION_CODE, info.longVersionCode)
            dataMap.putString(P.KEY_VERSION_NAME, info.versionName.orEmpty())
            dataMap.putString(P.KEY_APK_SHA256, apkHash(app, info.lastUpdateTime))
            dataMap.putLong(P.KEY_POSITION_AT, System.currentTimeMillis())
        }
        runCatching {
            Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()), 10, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "info publish failed: ${it.message}") }
    }

    private fun apkHash(context: Context, installedAt: Long): String {
        val prefs = context.getSharedPreferences("self_update", Context.MODE_PRIVATE)
        if (prefs.getLong("hashed_at", -1) == installedAt) prefs.getString("hash", null)?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().putLong("hashed_at", installedAt).putString("hash", hash).apply()
        return hash
    }

    private fun incoming(context: Context) = File(context.cacheDir, "update/nuvx.apk")

    fun receive(context: Context, channel: ChannelClient.Channel) {
        val app = context.applicationContext
        val file = incoming(app).apply {
            parentFile?.mkdirs()
            delete()
        }
        runCatching {
            Tasks.await(Wearable.getChannelClient(app).receiveFile(channel, Uri.fromFile(file), false), 30, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "update receive failed", it) }
    }

    fun onReceived(context: Context, channel: ChannelClient.Channel, closeReason: Int) {
        val app = context.applicationContext
        val file = incoming(app)
        if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || !file.exists()) {
            Log.w(TAG, "update transfer ended early ($closeReason)")
            file.delete()
            return
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val result = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(app, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "update install failed", e)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
        } finally {
            file.delete()
        }
    }

    val pendingConfirm = MutableStateFlow<Intent?>(null)

    internal fun askToConfirm(context: Context, confirm: Intent) {
        pendingConfirm.value = confirm
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val tap = PendingIntent.getActivity(
            context,
            0,
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_ready))
            .setContentText(context.getString(R.string.update_tap))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    internal fun done(context: Context) {
        pendingConfirm.value = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { SelfUpdate.askToConfirm(context, it) }
            }
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.done(context)
            else -> Log.w(
                "NuvxWear",
                "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
            )
        }
    }
}
