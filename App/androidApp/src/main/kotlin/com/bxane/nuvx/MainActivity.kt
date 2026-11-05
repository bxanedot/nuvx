package com.bxane.nuvx

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.C
import com.bxane.nuvx.ui.NuvxApp
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private lateinit var connection: ListenableFuture<MediaController>
    private var player by mutableStateOf<AndroidPlayer?>(null)
    private var connectionError by mutableStateOf(false)
    private var pipEligible = false
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPictureInPicture = isInPictureInPictureMode
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        connection = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connection.addListener({
            if (!isDestroyed) {
                try { player = AndroidPlayer(connection.get()) } catch (_: Exception) { connectionError = true }
            }
        }, ContextCompat.getMainExecutor(this))
        val app = application as NuvxApplication
        val watch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        val tv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasSystemEqualizer = !watch && !tv && packageManager.resolveActivity(
            Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL), PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
        setContent {
            var localLibraryForPermission by remember { mutableStateOf<AndroidLocalMusic?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                localLibraryForPermission?.onPermissionResult(it)
            }
            val equalizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            val localLibrary = remember(permissionLauncher) {
                AndroidLocalMusic(this@MainActivity, permissionLauncher::launch).also { localLibraryForPermission = it }
            }
            DisposableEffect(localLibrary) { onDispose { localLibrary.close() } }
            val ready = player
            if (ready != null) {
                val reduceMotionEnabled = reducedMotion(contentResolver)
                val overscrollFactory = rememberRubberBandOverscrollFactory()
                val playbackState by ready.state.collectAsState()
                val pipSupported = !watch && !tv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                val canEnterPip = playbackState.canEnterPictureInPicture(pipSupported, watch, tv)
                LaunchedEffect(canEnterPip) {
                    pipEligible = canEnterPip
                    if (pipSupported) {
                        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(canEnterPip)
                        }.build()
                        setPictureInPictureParams(params)
                    }
                }
                CompositionLocalProvider(LocalOverscrollFactory provides if (reduceMotionEnabled) null else overscrollFactory) {
                    AndroidGlassHost {
                    NuvxApp(app.router, app.store, ready, app.lyrics,
                        watch = watch,
                        tv = tv,
                        androidUi = true,
                        pictureInPicture = inPictureInPicture,
                        localLibrary = localLibrary,
                        reduceMotion = reduceMotionEnabled,
                        onBackHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
                        onSystemBarIconsDark = { darkIcons ->
                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                isAppearanceLightStatusBars = darkIcons
                                isAppearanceLightNavigationBars = darkIcons
                            }
                        },
                        canvasVideo = { url, modifier -> CanvasVideo(url, modifier) },
                        videoSurface = { modifier -> VideoSurface(ready, modifier) },
                        openSystemEqualizer = if (!hasSystemEqualizer) null else ({
                            val sessionId = ready.audioSessionId()
                            if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
                                Toast.makeText(this@MainActivity, "Start playback before opening the system equalizer.", Toast.LENGTH_SHORT).show()
                            } else try {
                                equalizerLauncher.launch(audioEffectSessionIntent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, sessionId))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(this@MainActivity, "No system equalizer is available on this device.", Toast.LENGTH_SHORT).show()
                            }
                        }))
                    }
                }
            } else MaterialTheme { Surface { Text(if (connectionError) "Could not connect to playback. Reopen nuvx to retry." else "Connecting to nuvx…") } }
        }
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        player?.close()
        MediaController.releaseFuture(connection)
        super.onDestroy()
    }
}
