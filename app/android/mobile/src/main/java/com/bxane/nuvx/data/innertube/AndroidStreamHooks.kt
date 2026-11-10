package com.bxane.nuvx.data.innertube

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bxane.nuvx.BuildConfig
import com.bxane.nuvx.data.MonotonicClock
import com.bxane.nuvx.data.TrackLog
import android.webkit.CookieManager
import com.bxane.nuvx.data.innertube.potoken.PoTokenGenerator
import com.bxane.nuvx.data.innertube.potoken.PoTokenWebView
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceResolver

object AndroidStreamHooks {

    fun installEarly() {
        MonotonicClock.nowMs = SystemClock::elapsedRealtime
        if (BuildConfig.DEBUG) {
            TrackLog.echo = { level, tag, message, error ->
                when (level) {
                    'D' -> Log.d(tag, message, error)
                    'I' -> Log.i(tag, message, error)
                    'W' -> Log.w(tag, message, error)
                    else -> Log.e(tag, message, error)
                }
            }
        }
        TrackLog.sourcesLine = {
            "sources: substitution=${SourceResolver.canSubstituteForYouTube()} " +
                "request=${SourceResolver.requestForNow()}"
        }
        TrackLog.headerLines = {
            listOf(
                "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            )
        }
        StreamResolver.maxKbps = { AppSettings.effectiveAudioQuality.maxKbps }
        StreamResolver.fallbackEngine = { ExcorFallback.takeIf { AppSettings.excorFallback.value } }
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
    }

    fun initInnerTubeX(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        val poTokens = PoTokenGenerator(
            createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
            available = { webViewSupported },
        )
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
            poTokenProvider = poTokens.asTokenProvider(),
        )
    }
}
