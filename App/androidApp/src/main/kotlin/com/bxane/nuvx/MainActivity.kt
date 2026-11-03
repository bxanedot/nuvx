package com.bxane.nuvx

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bxane.nuvx.ui.NuvxApp
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private lateinit var connection: ListenableFuture<MediaController>
    private var player by mutableStateOf<AndroidPlayer?>(null)
    private var connectionError by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        connection = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connection.addListener({
            if (!isDestroyed) {
                try { player = AndroidPlayer(connection.get()) } catch (_: Exception) { connectionError = true }
            }
        }, ContextCompat.getMainExecutor(this))
        val app = application as NuvxApplication
        setContent {
            var localLibraryForPermission by remember { mutableStateOf<AndroidLocalMusic?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                localLibraryForPermission?.onPermissionResult(it)
            }
            val localLibrary = remember(permissionLauncher) {
                AndroidLocalMusic(this@MainActivity, permissionLauncher::launch).also { localLibraryForPermission = it }
            }
            DisposableEffect(localLibrary) { onDispose { localLibrary.close() } }
            val ready = player
            if (ready != null) NuvxApp(app.router, app.store, ready, app.lyrics,
                watch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH),
                tv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                localLibrary = localLibrary,
                reduceMotion = reducedMotion(contentResolver),
                onBackHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
                canvasVideo = { url, modifier -> CanvasVideo(url, modifier) })
            else MaterialTheme { Surface { Text(if (connectionError) "Could not connect to playback. Reopen nuvx to retry." else "Connecting to nuvx…") } }
        }
    }

    override fun onDestroy() {
        player?.close()
        MediaController.releaseFuture(connection)
        super.onDestroy()
    }
}
