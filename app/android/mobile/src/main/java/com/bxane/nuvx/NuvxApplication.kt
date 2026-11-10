package com.bxane.nuvx

import android.app.Application
import androidx.annotation.OptIn
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.lyrics.LyricsTranslation
import androidx.appcompat.app.AppCompatDelegate
import com.bxane.nuvx.ui.player.AndroidPlayerHost
import com.bxane.nuvx.ui.player.PlayerPlatform
import com.bxane.nuvx.data.canvas.CanvasCache
import com.bxane.nuvx.data.smb.SmbCoverFetcher
import com.bxane.nuvx.data.webdav.WebDavCoilAuth
import com.bxane.nuvx.data.canvas.SpotifyToken
import com.bxane.nuvx.data.spotify.SpotifyLibrary
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.LastPlayed
import com.bxane.nuvx.playback.BoundedContextPersistence
import com.bxane.nuvx.playback.OriginalVersion
import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.AndroidStreamHooks
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.scrobbling.LastFM
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.SearchHistory
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.stats.ArtistFacts
import com.bxane.nuvx.data.stats.ListeningStats
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.download.PlaylistSync
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class NuvxApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        AndroidStreamHooks.installEarly()
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.bxane.nuvx.ui.AppUi.install(com.bxane.nuvx.ui.AndroidAppUiHost)
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        } else {
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            java.util.logging.LogManager.getLogManager().reset()
            java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        }
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
        }
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        LyricsTranslation.cacheDir = cacheDir
        authStore = AuthStore(this)
        val backgroundInit = thread(name = "startup-init") {
            SourceRegistry.init(this)
            AndroidStreamHooks.initInnerTubeX(this)
            CanvasCache.init(this)
        }
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        if (restoredSession != null) {
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }
        }
        AppSettings.init(this, authStore)
        com.bxane.nuvx.data.sources.addon.AddonAuth.init(this)
        com.bxane.nuvx.playback.audio.LosslessOutput.init(this)
        ListenTogether.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        BoundedContextPersistence.init(this)
        com.bxane.nuvx.playback.PartyPersonalQueueStash.init(this)
        OriginalVersion.init(this)
        Downloads.init(this)
        PlaylistSync.init(this)
        ListeningStats.init(this)
        ArtistFacts.init(this)
        AudioCache.init(this)
        SpotifyToken.init(this)
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        initLastfm()
        com.bxane.nuvx.data.presence.AndroidPresence.install(this)
        backgroundInit.join()
    }

    private val coilHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "nuvx/${BuildConfig.VERSION_NAME} (Android; +https://github.com/bxanedot/nuvx)",
                        )
                        .build(),
                )
            }
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(WebDavCoilAuth())
                add(SmbCoverFetcher.Factory())
                add(SvgDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(200)
            .allowHardware(!emulatedGpu)
            .build()

    private val emulatedGpu: Boolean by lazy {
        listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib")
            .any { java.io.File("$it/egl/libEGL_emulation.so").exists() }
    }

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        lateinit var authStore: AuthStore
            private set

        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }
}
