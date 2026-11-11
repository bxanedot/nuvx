package com.bxane.nuvx

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.listentogether.JamInviteLink
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.ThemeMode
import com.bxane.nuvx.ui.components.handleDacVolumeKey
import com.bxane.nuvx.playback.MusicLink
import com.bxane.nuvx.playback.PlayerDeepLink
import com.bxane.nuvx.ui.components.LocalAppBackdrop
import com.bxane.nuvx.ui.components.LocalLiquidGlassEnabled
import com.bxane.nuvx.ui.components.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.bxane.nuvx.ui.theme.NuvxTheme
import com.bxane.nuvx.ui.utils.rememberIosOverscrollFactory
import com.bxane.nuvx.ui.performance.resolvePerformanceRefreshRate
import kotlinx.coroutines.launch
import com.bxane.nuvx.ui.intro.NuvxIntro
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import androidx.compose.ui.res.vectorResource
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope


internal fun shouldSkipAfterDislike(
    previousStatus: LikeStatus,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != LikeStatus.DISLIKE && targetVideoId == currentVideoId

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            AppSettings.carMode.collect { car ->
                requestedOrientation = if (car) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (car) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
        val playIntro = savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        setContent {
            var showIntro by rememberSaveable { mutableStateOf(playIntro) }
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            NuvxTheme(darkTheme = darkTheme, font = appFont) {
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    NuvxApp(
                        darkTheme = darkTheme,
                        windowWidth = maxWidth,
                        windowHeight = maxHeight,
                        appBackdrop = appBackdrop,
                    )
                    if (showIntro) {
                        val haptics = rememberHaptics()
                        NuvxIntro(
                            logo = ImageVector.vectorResource(R.drawable.ic_logo),
                            onBeat = { haptics.play(Haptic.Pulse) },
                            onFinished = { showIntro = false },
                        )
                    }
                }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleDacVolumeKey(event) || super.dispatchKeyEvent(event)

    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
    }
}
