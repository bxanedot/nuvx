package com.bxane.nuvx

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.bxane.nuvx.data.listentogether.JamInviteLink
import com.bxane.nuvx.playback.MusicLink
import com.bxane.nuvx.playback.PlayerDeepLink
import com.bxane.nuvx.playback.rememberMediaController
import com.bxane.nuvx.playback.rememberPlayerState
import com.bxane.nuvx.ui.MainViewModel
import com.bxane.nuvx.ui.theme.NuvxTheme
import com.bxane.nuvx.ui.tv.TvApp
import com.bxane.nuvx.ui.tv.display.TvRefreshRateController
import com.bxane.nuvx.ui.tv.theme.NuvxTvTheme
import com.bxane.nuvx.ui.intro.NuvxIntro
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bxane.nuvx.R

/**
 * Dedicated TV Activity for Android TV, Google TV, and Fire TV devices.
 * Launched via `android.intent.category.LEANBACK_LAUNCHER`.
 */
class TvActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Attach TV Refresh Rate and Display Controller
        TvRefreshRateController.attach(this)

        // Make window full edge-to-edge for 1080p/4K television screens
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        // Cold launches from the launcher only, as on the phone.
        val playIntro = savedInstanceState == null && intent?.categories.orEmpty()
            .any { it == Intent.CATEGORY_LAUNCHER || it == Intent.CATEGORY_LEANBACK_LAUNCHER }
        setContent {
            var showIntro by rememberSaveable { mutableStateOf(playIntro) }
            NuvxTvTheme {
                // The phone's Material theme too, so the components shared with
                // it (lyrics, mood sleeves) are set in SF Pro on its type scale.
                NuvxTheme(darkTheme = true) {
                    val mediaController = rememberMediaController()
                    val playerState = rememberPlayerState(mediaController)

                    Box(Modifier.fillMaxSize()) {
                        TvApp(
                            viewModel = viewModel,
                            mediaController = mediaController,
                            playerState = playerState,
                        )
                        // A TV has no motor, so the beats are the waves alone.
                        if (showIntro) NuvxIntro(
                            logo = ImageVector.vectorResource(R.drawable.ic_logo),
                            onBeat = {},
                            onFinished = { showIntro = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLinks(intent)
    }

    private fun consumeLinks(intent: Intent) {
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
    }

    override fun onDestroy() {
        // Closing the app (back out of Home, or the task being swiped away)
        // pauses the music rather than leaving it playing behind the launcher.
        if (isFinishing) {
            runCatching {
                val future = androidx.media3.session.MediaController.Builder(
                    applicationContext,
                    androidx.media3.session.SessionToken(
                        applicationContext,
                        android.content.ComponentName(
                            applicationContext,
                            com.bxane.nuvx.playback.PlaybackService::class.java,
                        ),
                    ),
                ).buildAsync()
                future.addListener({
                    runCatching { future.get().run { pause(); release() } }
                }, androidx.core.content.ContextCompat.getMainExecutor(applicationContext))
            }
        }
        super.onDestroy()
        TvRefreshRateController.detach()
    }
}
