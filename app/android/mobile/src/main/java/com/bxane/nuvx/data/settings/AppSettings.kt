package com.bxane.nuvx.data.settings

import com.bxane.nuvx.data.webdav.update
import com.bxane.nuvx.ui.components.VideoThumbnails
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.data.canvas.SpotifyToken
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.playback.EqLayout
import com.bxane.nuvx.playback.EqualizerPreset
import kotlinx.coroutines.flow.MutableStateFlow


fun AudioQuality.permits(kind: SourceKind): Boolean = when (this) {
    AudioQuality.LOSSLESS -> true
    AudioQuality.HIGH -> !kind.canServeLossless
    AudioQuality.MEDIUM, AudioQuality.LOW -> kind == SourceKind.YOUTUBE
}

enum class OutputPcmMode(val label: String) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

enum class BitPerfectMode {
    OFF,

    USB_DIRECT,

    DAP_EXCLUSIVE,

    USB_ANDROID,
}

enum class DownloadQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val perTrack: String,
    val keepsLossless: Boolean,
) {
    STANDARD(128, "Standard", "~128 kbps Opus · fits more on the device", "~4 MB", false),
    HIGH(Int.MAX_VALUE, "High", "Best audio on offer; source quality first", "~8 MB", false),
    LOSSLESS(
        Int.MAX_VALUE,
        "Lossless",
        "Bit-exact if a source has it, best Opus if not",
        "~35 MB",
        true,
    ),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

enum class AppFont { DEFAULT, LEGACY }

enum class EqualizerMode {
    DYNAMIC,

    MANUAL,
}

enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

enum class SongSort {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}

object AppSettings {

    private lateinit var prefs: SharedPreferences

    private val discordSettings = DiscordSettingsController()
    private val scrobblingSettings = ScrobblingSettingsController()
    private val librarySettings = LibrarySettingsController(MAX_PINNED_PLAYLISTS)
    private val lyricsSettings = LyricsSettingsController(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
    private val remoteStorageSettings = RemoteStorageSettingsController()

    val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)

    val upgradeLengthSlackSeconds = MutableStateFlow(DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS)

    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)

    val wifiOnlyDownloads = MutableStateFlow(true)

    val exportDownloads = MutableStateFlow(false)

    val syncDownloadedPlaylists = MutableStateFlow(false)

    val meteredConnection = MutableStateFlow<Boolean?>(null)

    val crossfadeSeconds = MutableStateFlow(0)

    val smartFadeEnabled = MutableStateFlow(false)

    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
    val skipSilence = MutableStateFlow(false)

    val excorFallback = MutableStateFlow(true)

    val outputPcmMode = MutableStateFlow(OutputPcmMode.PCM_16)

    val preferUsbDac = MutableStateFlow(false)

    val bitPerfectMode = MutableStateFlow(BitPerfectMode.OFF)

    val usbAllowFixedVolume = MutableStateFlow(false)

    val convertForLimitedDac = MutableStateFlow(false)

    val loudnessNormalization = MutableStateFlow(true)

    val loudnessOffOnSpeaker = MutableStateFlow(true)

    val dolbyAtmos = MutableStateFlow(true)

    val directSourceStream = MutableStateFlow(false)

    val spatialAudio = MutableStateFlow(true)

    val equalizerEnabled = MutableStateFlow(false)

    val equalizerMode = MutableStateFlow(EqualizerMode.DYNAMIC)

    val equalizerToneX = MutableStateFlow(0)

    val equalizerToneY = MutableStateFlow(0)

    val equalizerFocused = MutableStateFlow(false)

    val equalizerBalance = MutableStateFlow(0f)

    val equalizerBands = MutableStateFlow(EqualizerPreset.FLAT.bands)

    val equalizerPreset = MutableStateFlow(EqualizerPreset.FLAT)

    val playbackSpeed = MutableStateFlow(1.0f)
    val themeMode = MutableStateFlow(ThemeMode.LIGHT)
    val appFont = MutableStateFlow(AppFont.DEFAULT)

    val autoplay = MutableStateFlow(true)

    val shuffleEnabled = MutableStateFlow(false)

    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

    val showNerdStats = MutableStateFlow(false)

    val showListenTogetherButton = MutableStateFlow(true)

    val reduceAnimation = MutableStateFlow(false)

    val onboardingComplete = MutableStateFlow(false)

    val classicSearch = MutableStateFlow(false)

    val youtubeSearch = MutableStateFlow(false)

    val carMode = MutableStateFlow(false)

    val hideRadioTab = MutableStateFlow(false)

    val radioIntroShown = MutableStateFlow(false)

    val hidePodcastsTab = MutableStateFlow(false)

    val podcastsIntroShown = MutableStateFlow(false)

    val supportPromptsOff = MutableStateFlow(false)

    val diwaliGreetingShown = MutableStateFlow(false)

    val highPerformanceMode = MutableStateFlow(false)

    val performanceRefreshRate = MutableStateFlow(DEFAULT_PERFORMANCE_REFRESH_RATE)

    val stopOnTaskRemoved = MutableStateFlow(false)

    val hideVolumeBar = MutableStateFlow(false)

    val hideStatusBarInPlayer = MutableStateFlow(false)

    val hideSongStatus = MutableStateFlow(false)

    val showQueueHistory = MutableStateFlow(false)

    val swipeToPlayNext = MutableStateFlow(false)

    val dontRepeatSuggestions = MutableStateFlow(false)

    val preferMusicOnly = MutableStateFlow(false)

    val hideRecents = MutableStateFlow(false)

    val hideHomeVideos = MutableStateFlow(false)

    val hideTopPicks = MutableStateFlow(false)

    val hideHomeMoods = MutableStateFlow(false)

    val squareVideoThumbnails = MutableStateFlow(false)

    val smartVersionAlignment = MutableStateFlow(true)

    val reduceDynamicBlur = MutableStateFlow(false)

    val disableBlendEffects = MutableStateFlow(false)

    val liquidGlass = MutableStateFlow(true)

    val glassPerformance = MutableStateFlow(true)

    val progressiveBlur = MutableStateFlow(false)

    val lyricsBlur = lyricsSettings.lyricsBlur

    val lyricsOffsetMs = lyricsSettings.lyricsOffsetMs

    val translationLanguage = lyricsSettings.translationLanguage

    val animatedCanvas = MutableStateFlow(true)

    val canvasOverCellular = MutableStateFlow(false)

    val spotifyCanvasAutoHide = MutableStateFlow(true)

    val prioritizeSpotifyCanvas = MutableStateFlow(false)

    val signatureApiEnabled = MutableStateFlow(false)
    val signatureApiUrl = MutableStateFlow("")

    val fullBleedArtwork = MutableStateFlow(true)

    val fullBleedDetailArtwork = MutableStateFlow(false)

    val playerTheme = MutableStateFlow(PlayerTheme.DEFAULT)

    val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)

    val syncedLyrics = lyricsSettings.syncedLyrics

    val lyricsSources = lyricsSettings.lyricsSources

    val lyricsSourceOrder = lyricsSettings.lyricsSourceOrder

    val prioritizeSyllableSync = lyricsSettings.prioritizeSyllableSync

    val paxSenixApiKey = lyricsSettings.paxSenixApiKey

    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    val showCacheFolder = MutableStateFlow(false)

    val replayGenres = MutableStateFlow(true)

    val filterNonMusicAudio = MutableStateFlow(true)

    val localMusicSort = librarySettings.localMusicSort
    val downloadedMusicSort = librarySettings.downloadedMusicSort
    val localMusicViewType = librarySettings.localMusicViewType
    val downloadedMusicViewType = librarySettings.downloadedMusicViewType
    val librarySort = librarySettings.librarySort

    val detailSongSorts = librarySettings.detailSongSorts

    val localMusicFolderUri = librarySettings.localMusicFolderUri

    val webdavUrl = remoteStorageSettings.webdavUrl
    val webdavUsername = remoteStorageSettings.webdavUsername
    val webdavPassword = remoteStorageSettings.webdavPassword
    val smbHost = remoteStorageSettings.smbHost
    val smbShare = remoteStorageSettings.smbShare
    val smbBasePath = remoteStorageSettings.smbBasePath
    val smbUsername = remoteStorageSettings.smbUsername
    val smbPassword = remoteStorageSettings.smbPassword

    val pinnedPlaylists = librarySettings.pinnedPlaylists

    const val MAX_PINNED_PLAYLISTS = 5

    val scrobblingAvailable = true

    val lastfmEnabled = scrobblingSettings.lastfmEnabled
    val lastfmUsername = scrobblingSettings.lastfmUsername
    val lastfmSessionKey = scrobblingSettings.lastfmSessionKey
    val lastfmApiKey = scrobblingSettings.lastfmApiKey
    val lastfmSecret = scrobblingSettings.lastfmSecret
    val lastfmEndpoint = scrobblingSettings.lastfmEndpoint
    val lastfmScrobbleEnabled = scrobblingSettings.lastfmScrobbleEnabled
    val lastfmNowPlaying = scrobblingSettings.lastfmNowPlaying
    val lastfmPrimaryArtistOnly = scrobblingSettings.lastfmPrimaryArtistOnly
    val scrobbleMinDuration = scrobblingSettings.scrobbleMinDuration
    val scrobbleDelayPercent = scrobblingSettings.scrobbleDelayPercent
    val scrobbleDelaySeconds = scrobblingSettings.scrobbleDelaySeconds
    val listenBrainzEnabled = scrobblingSettings.listenBrainzEnabled
    val listenBrainzToken = scrobblingSettings.listenBrainzToken
    val listenBrainzPrimaryArtistOnly = scrobblingSettings.listenBrainzPrimaryArtistOnly
    val spotifySpdcToken = MutableStateFlow("")

    val discordToken = discordSettings.discordToken
    val discordUsername = discordSettings.discordUsername
    val discordName = discordSettings.discordName
    val discordAvatar = discordSettings.discordAvatar
    val discordRpcEnabled = discordSettings.discordRpcEnabled
    val discordUseDetails = discordSettings.discordUseDetails
    val discordShowAudioQuality = discordSettings.discordShowAudioQuality
    val discordAdvancedMode = discordSettings.discordAdvancedMode
    val discordStatus = discordSettings.discordStatus
    val discordActivityType = discordSettings.discordActivityType
    val discordActivityName = discordSettings.discordActivityName
    val discordButton1Text = discordSettings.discordButton1Text
    val discordButton1Visible = discordSettings.discordButton1Visible
    val discordButton2Text = discordSettings.discordButton2Text
    val discordButton2Visible = discordSettings.discordButton2Visible
    val discordInfoDismissed = discordSettings.discordInfoDismissed

    val audioSessionId = MutableStateFlow(0)

    val smartMixInProgress = MutableStateFlow(false)

    val smartMixBlend = MutableStateFlow<MixBlend?>(null)

    val smartMixView = MutableStateFlow<MixTransitionView?>(null)

    val smartMixProgress = MutableStateFlow(0f)

    val smartMixPreview = MutableStateFlow<MixTransitionView?>(null)

    val mixViewOpen = MutableStateFlow(false)

    val versionAlignmentInProgress = MutableStateFlow(false)

    val smartAnalysis = MutableStateFlow(SmartAnalysis())

    val automixDetail = MutableStateFlow<String?>(null)

    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)

    val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)

    val effectiveAudioQuality: AudioQuality
        get() = ceilingFor(
            wifi = audioQualityWifi.value,
            cellular = audioQualityCellular.value,
            metered = meteredConnection.value,
            override = streamCeilingOverride.value,
        )

    fun ceilingFor(
        wifi: AudioQuality,
        cellular: AudioQuality,
        metered: Boolean?,
        override: AudioQuality?,
    ): AudioQuality {
        val chosen = if (metered == true) cellular else wifi
        val cap = override ?: return chosen
        return if (chosen.ordinal > cap.ordinal) cap else chosen
    }

    val downloadsAllowedNow: Boolean
        get() = !wifiOnlyDownloads.value || meteredConnection.value != true

    fun init(context: Context, authStore: AuthStore) {
        prefs = context.getSharedPreferences("nuvx_settings", Context.MODE_PRIVATE)
        remoteStorageSettings.initialize(prefs, authStore)
        com.bxane.nuvx.data.spotify.LocalPlaylistStore.init(context)
        com.bxane.nuvx.data.OfflinePlaylists.init(
            context,
            likedTitle = context.getString(com.bxane.nuvx.R.string.auto_liked),
        )
        discordSettings.initialize(prefs, authStore)
        scrobblingSettings.initialize(prefs)
        librarySettings.initialize(prefs)
        lyricsSettings.initialize(prefs)
        readAll()
        MeteredConnectionObserver.watch(context, meteredConnection)
    }

    fun reload() {
        if (!this::prefs.isInitialized) return
        readAll()
    }

    private fun readAll() {
        migrateSingleQuality()
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        upgradeLengthSlackSeconds.value = prefs.getInt(
            KEY_UPGRADE_LENGTH_SLACK_SECONDS,
            DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS,
        ).coerceIn(MIN_UPGRADE_LENGTH_SLACK_SECONDS, MAX_UPGRADE_LENGTH_SLACK_SECONDS)
        migrateDownloadQuality()
        downloadQuality.value = readDownloadQuality()
        wifiOnlyDownloads.value = prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        exportDownloads.value = prefs.getBoolean(KEY_EXPORT_DOWNLOADS, false)
        syncDownloadedPlaylists.value = prefs.getBoolean(KEY_SYNC_DOWNLOADED_PLAYLISTS, false)
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE, 0)
        smartFadeEnabled.value = prefs.getBoolean(KEY_SMART_FADE, false)
        automixPerformanceMode.value = runCatching {
            AutomixPerformanceMode.valueOf(
                prefs.getString(KEY_AUTOMIX_PERFORMANCE_MODE, null) ?: AutomixPerformanceMode.BALANCED.name,
            )
        }.getOrDefault(AutomixPerformanceMode.BALANCED)
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        excorFallback.value = prefs.getBoolean(KEY_EXCOR_FALLBACK, true)
        outputPcmMode.value = runCatching {
            OutputPcmMode.valueOf(
                prefs.getString(KEY_OUTPUT_PCM_MODE, OutputPcmMode.PCM_16.name)
                    ?: OutputPcmMode.PCM_16.name,
            )
        }.getOrDefault(OutputPcmMode.PCM_16)
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        bitPerfectMode.value = runCatching {
            BitPerfectMode.valueOf(prefs.getString(KEY_BIT_PERFECT_MODE, null) ?: BitPerfectMode.OFF.name)
        }.getOrDefault(BitPerfectMode.OFF)
        usbAllowFixedVolume.value = prefs.getBoolean(KEY_USB_ALLOW_FIXED_VOLUME, false)
        convertForLimitedDac.value = prefs.getBoolean(KEY_CONVERT_FOR_LIMITED_DAC, false)
        loudnessNormalization.value = prefs.getBoolean(KEY_LOUDNESS_NORMALIZATION, true)
        loudnessOffOnSpeaker.value = prefs.getBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, true)
        dolbyAtmos.value = prefs.getBoolean(KEY_DOLBY_ATMOS, true)
        directSourceStream.value = prefs.getBoolean(KEY_DIRECT_SOURCE_STREAM, false)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, true)
        equalizerEnabled.value = prefs.getBoolean(KEY_EQ_ENABLED, false)
        equalizerMode.value = runCatching {
            EqualizerMode.valueOf(prefs.getString(KEY_EQ_MODE, null) ?: EqualizerMode.DYNAMIC.name)
        }.getOrDefault(EqualizerMode.DYNAMIC)
        equalizerToneX.value = prefs.getInt(KEY_EQ_TONE_X, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        equalizerToneY.value = prefs.getInt(KEY_EQ_TONE_Y, 0).coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
        equalizerFocused.value = prefs.getBoolean(KEY_EQ_FOCUSED, false)
        equalizerBalance.value = prefs.getFloat(KEY_EQ_BALANCE, 0f).coerceIn(-1f, 1f)
        equalizerBands.value = readEqualizerBands()
        equalizerPreset.value = EqualizerPreset.matching(equalizerBands.value)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "LIGHT")
        }.getOrDefault(ThemeMode.LIGHT)
        appFont.value = runCatching {
            AppFont.valueOf(prefs.getString(KEY_APP_FONT, null) ?: AppFont.DEFAULT.name)
        }.getOrDefault(AppFont.DEFAULT)
        autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        shuffleEnabled.value = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        repeatMode.value = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        showListenTogetherButton.value = prefs.getBoolean(KEY_LISTEN_TOGETHER_BUTTON, true)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        onboardingComplete.value = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        diwaliGreetingShown.value = prefs.getBoolean(KEY_DIWALI_GREETING_SHOWN, false)
        hideRadioTab.value = prefs.getBoolean(KEY_HIDE_RADIO_TAB, false)
        carMode.value = prefs.getBoolean(KEY_CAR_MODE, false)
        classicSearch.value = prefs.getBoolean(KEY_CLASSIC_SEARCH, false)
        youtubeSearch.value = prefs.getBoolean(KEY_YOUTUBE_SEARCH, false)
        radioIntroShown.value = prefs.getBoolean(KEY_RADIO_INTRO_SHOWN, false)
        hidePodcastsTab.value = prefs.getBoolean(KEY_HIDE_PODCASTS_TAB, false)
        podcastsIntroShown.value = prefs.getBoolean(KEY_PODCASTS_INTRO_SHOWN, false)
        supportPromptsOff.value = prefs.getBoolean(KEY_SUPPORT_PROMPTS_OFF, false)
        highPerformanceMode.value = prefs.getBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        performanceRefreshRate.value = normalizePerformanceRefreshRate(
            prefs.getInt(KEY_PERFORMANCE_REFRESH_RATE, DEFAULT_PERFORMANCE_REFRESH_RATE),
        )
        stopOnTaskRemoved.value = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, false)
        hideVolumeBar.value = prefs.getBoolean(KEY_HIDE_VOLUME_BAR, false)
        hideStatusBarInPlayer.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, false)
        hideSongStatus.value = prefs.getBoolean(KEY_HIDE_SONG_STATUS, false)
        showQueueHistory.value = prefs.getBoolean(KEY_SHOW_QUEUE_HISTORY, false)
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        dontRepeatSuggestions.value = prefs.getBoolean(KEY_DONT_REPEAT_SUGGESTIONS, false)
        preferMusicOnly.value = prefs.getBoolean(KEY_PREFER_MUSIC_ONLY, false)
        hideRecents.value = prefs.getBoolean(KEY_HIDE_RECENTS, false)
        hideHomeVideos.value = prefs.getBoolean(KEY_HIDE_HOME_VIDEOS, false)
        hideTopPicks.value = prefs.getBoolean(KEY_HIDE_TOP_PICKS, false)
        hideHomeMoods.value = prefs.getBoolean(KEY_HIDE_HOME_MOODS, false)
        squareVideoThumbnails.value = prefs.getBoolean(KEY_SQUARE_VIDEO_THUMBNAILS, false)
        VideoThumbnails.square = squareVideoThumbnails.value
        smartVersionAlignment.value = prefs.getBoolean(KEY_SMART_VERSION_ALIGNMENT, true)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        disableBlendEffects.value = prefs.getBoolean(KEY_DISABLE_BLEND_EFFECTS, false)
        liquidGlass.value = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        glassPerformance.value = prefs.getBoolean(KEY_GLASS_PERFORMANCE, true)
        progressiveBlur.value = prefs.getBoolean(KEY_PROGRESSIVE_BLUR, false)
        lyricsSettings.readPresentationPreferences()
        if (highPerformanceMode.value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        canvasOverCellular.value = prefs.getBoolean(KEY_CANVAS_OVER_CELLULAR, false)
        spotifyCanvasAutoHide.value = prefs.getBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, true)
        prioritizeSpotifyCanvas.value = prefs.getBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, false)
        signatureApiEnabled.value = prefs.getBoolean(KEY_SIGNATURE_API_ENABLED, false)
        signatureApiUrl.value = normalizeSignatureApiUrl(prefs.getString(KEY_SIGNATURE_API_URL, "").orEmpty())
        fullBleedArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_ARTWORK, true)
        fullBleedDetailArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_DETAIL_ARTWORK, false)
        playerTheme.value = runCatching {
            PlayerTheme.valueOf(prefs.getString(KEY_PLAYER_THEME, null).orEmpty())
        }.getOrElse {
            if (prefs.getBoolean(KEY_LEGACY_MESH_GRADIENT, false)) PlayerTheme.LEGACY else PlayerTheme.DEFAULT
        }
        lastPlayerScreen.value = runCatching {
            LastPlayerScreen.valueOf(
                prefs.getString(KEY_LAST_PLAYER_SCREEN, null) ?: LastPlayerScreen.MAIN.name,
            )
        }.getOrDefault(LastPlayerScreen.MAIN)
        lyricsSettings.readProviderPreferences()
        audioCacheLimitBytes.value = clampCacheLimit(prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES))
        showCacheFolder.value = prefs.getBoolean(KEY_SHOW_CACHE_FOLDER, false)
        scrobblingSettings.read()
        spotifySpdcToken.value = prefs.getString(KEY_SPOTIFY_SPDC_TOKEN, "").orEmpty()
        replayGenres.value = prefs.getBoolean(KEY_REPLAY_GENRES, true)
        filterNonMusicAudio.value = prefs.getBoolean(KEY_FILTER_NON_MUSIC_AUDIO, true)
        librarySettings.readPreferences()
        remoteStorageSettings.read()
        librarySettings.readPinnedPlaylists()
        discordSettings.read()
    }

    fun consumeVersionUpdate(currentVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, currentVersionCode)
        if (last != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
        return currentVersionCode > last
    }

    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.LOSSLESS
        return runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOSSLESS)
    }

    private fun migrateDownloadQuality() {
        if (prefs.contains(KEY_QUALITY_DOWNLOAD)) return
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, DownloadQuality.LOSSLESS.name).apply()
    }

    private fun readDownloadQuality(): DownloadQuality {
        val stored = prefs.getString(KEY_QUALITY_DOWNLOAD, null) ?: return DownloadQuality.LOSSLESS
        return runCatching { DownloadQuality.valueOf(stored) }.getOrDefault(DownloadQuality.LOSSLESS)
    }

    fun setAutoplay(value: Boolean) {
        autoplay.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.edit().putInt(KEY_REPEAT_MODE, value).apply()
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        audioQualityWifi.value = value
        prefs.edit().putString(KEY_QUALITY_WIFI, value.name).apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        audioQualityCellular.value = value
        prefs.edit().putString(KEY_QUALITY_CELLULAR, value.name).apply()
    }

    fun setUpgradeLengthSlackSeconds(value: Int) {
        val normalized = value.coerceIn(
            MIN_UPGRADE_LENGTH_SLACK_SECONDS,
            MAX_UPGRADE_LENGTH_SLACK_SECONDS,
        )
        upgradeLengthSlackSeconds.value = normalized
        prefs.edit().putInt(KEY_UPGRADE_LENGTH_SLACK_SECONDS, normalized).apply()
    }

    fun setDownloadQuality(value: DownloadQuality) {
        downloadQuality.value = value
        prefs.edit().putString(KEY_QUALITY_DOWNLOAD, value.name).apply()
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        wifiOnlyDownloads.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()
    }

    fun setCrossfadeSeconds(value: Int) {
        crossfadeSeconds.value = value
        prefs.edit().putInt(KEY_CROSSFADE, value).apply()
    }

    fun setSmartFadeEnabled(value: Boolean) {
        smartFadeEnabled.value = value
        prefs.edit().putBoolean(KEY_SMART_FADE, value).apply()
    }

    fun setAutomixPerformanceMode(value: AutomixPerformanceMode) {
        automixPerformanceMode.value = value
        prefs.edit().putString(KEY_AUTOMIX_PERFORMANCE_MODE, value.name).apply()
    }

    fun setExcorFallback(value: Boolean) {
        excorFallback.value = value
        prefs.edit().putBoolean(KEY_EXCOR_FALLBACK, value).apply()
    }

    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    fun setDolbyAtmos(value: Boolean) {
        dolbyAtmos.value = value
        prefs.edit().putBoolean(KEY_DOLBY_ATMOS, value).apply()
    }

    fun setDirectSourceStream(value: Boolean) {
        directSourceStream.value = value
        prefs.edit().putBoolean(KEY_DIRECT_SOURCE_STREAM, value).apply()
    }

    fun setSpatialAudio(value: Boolean) {
        spatialAudio.value = value
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply()
    }

    fun setEqualizerEnabled(value: Boolean) {
        equalizerEnabled.value = value
        prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
    }

    fun setEqualizerMode(value: EqualizerMode) {
        equalizerMode.value = value
        prefs.edit().putString(KEY_EQ_MODE, value.name).apply()
    }

    fun setEqualizerTone(x: Int, y: Int) {
        val steps = EqLayout.TONE_STEPS
        val clampedX = x.coerceIn(-steps, steps)
        val clampedY = y.coerceIn(-steps, steps)
        equalizerToneX.value = clampedX
        equalizerToneY.value = clampedY
        prefs.edit().putInt(KEY_EQ_TONE_X, clampedX).putInt(KEY_EQ_TONE_Y, clampedY).apply()
    }

    fun setEqualizerFocused(value: Boolean) {
        equalizerFocused.value = value
        prefs.edit().putBoolean(KEY_EQ_FOCUSED, value).apply()
    }

    fun setEqualizerBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        equalizerBalance.value = clamped
        prefs.edit().putFloat(KEY_EQ_BALANCE, clamped).apply()
    }

    fun setEqualizerBands(values: List<Float>) {
        val clamped = List(EqLayout.MANUAL_COUNT) {
            values.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
        equalizerBands.value = clamped
        equalizerPreset.value = EqualizerPreset.matching(clamped)
        prefs.edit().putString(KEY_EQ_BANDS, clamped.joinToString(",")).apply()
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        if (preset == EqualizerPreset.CUSTOM) return
        setEqualizerBands(preset.bands)
    }

    private fun readEqualizerBands(): List<Float> {
        val stored = prefs.getString(KEY_EQ_BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            .orEmpty()
        return List(EqLayout.MANUAL_COUNT) {
            stored.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
    }

    fun setPlaybackSpeed(value: Float) {
        playbackSpeed.value = value
        prefs.edit().putFloat(KEY_SPEED, value).apply()
    }

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
        prefs.edit().putBoolean(KEY_NERD_STATS, value).apply()
    }

    fun setShowListenTogetherButton(value: Boolean) {
        showListenTogetherButton.value = value
        prefs.edit().putBoolean(KEY_LISTEN_TOGETHER_BUTTON, value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setAppFont(value: AppFont) {
        appFont.value = value
        prefs.edit().putString(KEY_APP_FONT, value.name).apply()
    }

    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setOnboardingComplete() {
        onboardingComplete.value = true
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    fun setYoutubeSearch(value: Boolean) {
        youtubeSearch.value = value
        prefs.edit().putBoolean(KEY_YOUTUBE_SEARCH, value).apply()
    }

    fun setClassicSearch(value: Boolean) {
        classicSearch.value = value
        prefs.edit().putBoolean(KEY_CLASSIC_SEARCH, value).apply()
    }

    fun setCarMode(value: Boolean) {
        carMode.value = value
        prefs.edit().putBoolean(KEY_CAR_MODE, value).apply()
    }

    fun setHideRadioTab(value: Boolean) {
        hideRadioTab.value = value
        prefs.edit().putBoolean(KEY_HIDE_RADIO_TAB, value).apply()
    }

    fun setRadioIntroShown() {
        radioIntroShown.value = true
        prefs.edit().putBoolean(KEY_RADIO_INTRO_SHOWN, true).apply()
    }

    fun setHidePodcastsTab(value: Boolean) {
        hidePodcastsTab.value = value
        prefs.edit().putBoolean(KEY_HIDE_PODCASTS_TAB, value).apply()
    }

    fun setPodcastsIntroShown() {
        podcastsIntroShown.value = true
        prefs.edit().putBoolean(KEY_PODCASTS_INTRO_SHOWN, true).apply()
    }

    fun setSupportPromptsOff() {
        supportPromptsOff.value = true
        prefs.edit().putBoolean(KEY_SUPPORT_PROMPTS_OFF, true).apply()
    }

    fun nextSupportPrompt(): Int {
        val n = prefs.getInt(KEY_LAUNCHES, 0) + 1
        prefs.edit().putInt(KEY_LAUNCHES, n).apply()
        if (supportPromptsOff.value || n % 12 != 8) return 0
        return if ((n / 12) % 2 == 0) 1 else 2
    }

    fun setDiwaliGreetingShown() {
        diwaliGreetingShown.value = true
        prefs.edit().putBoolean(KEY_DIWALI_GREETING_SHOWN, true).apply()
    }

    fun setStopOnTaskRemoved(value: Boolean) {
        stopOnTaskRemoved.value = value
        prefs.edit().putBoolean(KEY_STOP_ON_TASK_REMOVED, value).apply()
    }

    fun setHideVolumeBar(value: Boolean) {
        hideVolumeBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_VOLUME_BAR, value).apply()
    }

    fun setHideStatusBarInPlayer(value: Boolean) {
        hideStatusBarInPlayer.value = value
        prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, value).apply()
    }

    fun setHideSongStatus(value: Boolean) {
        hideSongStatus.value = value
        prefs.edit().putBoolean(KEY_HIDE_SONG_STATUS, value).apply()
    }

    fun setShowQueueHistory(value: Boolean) {
        showQueueHistory.value = value
        prefs.edit().putBoolean(KEY_SHOW_QUEUE_HISTORY, value).apply()
    }

    fun setSwipeToPlayNext(value: Boolean) {
        swipeToPlayNext.value = value
        prefs.edit().putBoolean(KEY_SWIPE_TO_PLAY_NEXT, value).apply()
    }

    fun setDontRepeatSuggestions(value: Boolean) {
        dontRepeatSuggestions.value = value
        prefs.edit().putBoolean(KEY_DONT_REPEAT_SUGGESTIONS, value).apply()
    }

    fun setPreferMusicOnly(value: Boolean) {
        preferMusicOnly.value = value
        prefs.edit().putBoolean(KEY_PREFER_MUSIC_ONLY, value).apply()
    }

    fun setHideRecents(value: Boolean) {
        hideRecents.value = value
        prefs.edit().putBoolean(KEY_HIDE_RECENTS, value).apply()
    }

    fun setHideHomeVideos(value: Boolean) {
        hideHomeVideos.value = value
        prefs.edit().putBoolean(KEY_HIDE_HOME_VIDEOS, value).apply()
    }

    fun setHideTopPicks(value: Boolean) {
        hideTopPicks.value = value
        prefs.edit().putBoolean(KEY_HIDE_TOP_PICKS, value).apply()
    }

    fun setHideHomeMoods(value: Boolean) {
        hideHomeMoods.value = value
        prefs.edit().putBoolean(KEY_HIDE_HOME_MOODS, value).apply()
    }

    fun setSquareVideoThumbnails(value: Boolean) {
        squareVideoThumbnails.value = value
        VideoThumbnails.square = value
        prefs.edit().putBoolean(KEY_SQUARE_VIDEO_THUMBNAILS, value).apply()
    }

    fun setSmartVersionAlignment(value: Boolean) {
        smartVersionAlignment.value = value
        prefs.edit().putBoolean(KEY_SMART_VERSION_ALIGNMENT, value).apply()
    }

    fun setDisableBlendEffects(value: Boolean) {
        disableBlendEffects.value = value
        prefs.edit().putBoolean(KEY_DISABLE_BLEND_EFFECTS, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_BLUR, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setLiquidGlass(value: Boolean) {
        liquidGlass.value = value
        prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
    }

    fun setGlassPerformance(value: Boolean) {
        glassPerformance.value = value
        prefs.edit().putBoolean(KEY_GLASS_PERFORMANCE, value).apply()
    }

    fun setProgressiveBlur(value: Boolean) {
        progressiveBlur.value = value
        prefs.edit().putBoolean(KEY_PROGRESSIVE_BLUR, value).apply()
    }

    fun setHighPerformanceMode(value: Boolean) {
        highPerformanceMode.value = value
        if (value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        val editor = prefs.edit().putBoolean(KEY_HIGH_PERFORMANCE_MODE, value)
        if (value) {
            editor.putBoolean(KEY_REDUCE_ANIMATION, false)
            editor.putBoolean(KEY_REDUCE_BLUR, false)
        }
        editor.apply()
    }

    fun setPerformanceRefreshRate(value: Int) {
        val normalized = normalizePerformanceRefreshRate(value)
        performanceRefreshRate.value = normalized
        prefs.edit().putInt(KEY_PERFORMANCE_REFRESH_RATE, normalized).apply()
    }

    fun setLyricsBlur(value: Boolean) = lyricsSettings.setLyricsBlur(value)

    fun setLyricsOffsetMs(value: Int) = lyricsSettings.setLyricsOffsetMs(value)

    fun setTranslationLanguage(value: String) = lyricsSettings.setTranslationLanguage(value)

    fun setSyncedLyrics(value: Boolean) = lyricsSettings.setSyncedLyrics(value)

    fun setLyricsSources(value: Set<LyricsSource>) = lyricsSettings.setLyricsSources(value)

    fun setLyricsSourceOrder(value: List<LyricsSource>) = lyricsSettings.setLyricsSourceOrder(value)

    fun setPrioritizeSyllableSync(value: Boolean) = lyricsSettings.setPrioritizeSyllableSync(value)

    fun setPaxSenixApiKey(value: String) = lyricsSettings.setPaxSenixApiKey(value)

    fun resetLyricsSourceSettings() = lyricsSettings.resetLyricsSourceSettings()

    fun setAnimatedCanvas(value: Boolean) {
        animatedCanvas.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_CANVAS, value).apply()
    }

    fun setCanvasOverCellular(value: Boolean) {
        canvasOverCellular.value = value
        prefs.edit().putBoolean(KEY_CANVAS_OVER_CELLULAR, value).apply()
    }

    fun setSpotifyCanvasAutoHide(value: Boolean) {
        spotifyCanvasAutoHide.value = value
        prefs.edit().putBoolean(KEY_SPOTIFY_CANVAS_AUTO_HIDE, value).apply()
    }

    fun setPrioritizeSpotifyCanvas(value: Boolean) {
        prioritizeSpotifyCanvas.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SPOTIFY_CANVAS, value).apply()
    }

    fun setSignatureApiEnabled(value: Boolean) {
        signatureApiEnabled.value = value
        prefs.edit().putBoolean(KEY_SIGNATURE_API_ENABLED, value).apply()
    }

    fun setSignatureApiUrl(value: String) {
        val normalized = normalizeSignatureApiUrl(value)
        signatureApiUrl.value = normalized
        prefs.edit().putString(KEY_SIGNATURE_API_URL, normalized).apply()
    }

    fun setFullBleedArtwork(value: Boolean) {
        fullBleedArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_ARTWORK, value).apply()
    }

    fun setFullBleedDetailArtwork(value: Boolean) {
        fullBleedDetailArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_DETAIL_ARTWORK, value).apply()
    }

    fun setPlayerTheme(value: PlayerTheme) {
        playerTheme.value = value
        prefs.edit().putString(KEY_PLAYER_THEME, value.name).apply()
    }

    fun setLastPlayerScreen(value: LastPlayerScreen) {
        if (lastPlayerScreen.value == value) return
        lastPlayerScreen.value = value
        prefs.edit().putString(KEY_LAST_PLAYER_SCREEN, value.name).apply()
    }

    fun setAudioCacheLimitBytes(value: Long) {
        val clamped = clampCacheLimit(value)
        audioCacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    private fun clampCacheLimit(value: Long): Long =
        if (value > MAX_CACHE_LIMIT_BYTES) UNLIMITED_CACHE_LIMIT_BYTES
        else value.coerceAtLeast(DEFAULT_CACHE_LIMIT_BYTES)

    fun setShowCacheFolder(value: Boolean) {
        showCacheFolder.value = value
        prefs.edit().putBoolean(KEY_SHOW_CACHE_FOLDER, value).apply()
    }

    fun setLastfmEnabled(value: Boolean) = scrobblingSettings.setLastfmEnabled(value)

    fun setLastfmUsername(value: String) = scrobblingSettings.setLastfmUsername(value)

    fun setLastfmSessionKey(value: String) = scrobblingSettings.setLastfmSessionKey(value)

    fun setLastfmApiKey(value: String) = scrobblingSettings.setLastfmApiKey(value)

    fun setLastfmSecret(value: String) = scrobblingSettings.setLastfmSecret(value)

    fun setLastfmEndpoint(value: String) = scrobblingSettings.setLastfmEndpoint(value)

    fun setSpotifySpdcToken(value: String) {
        spotifySpdcToken.value = value
        prefs.edit().putString(KEY_SPOTIFY_SPDC_TOKEN, value).apply()
        SpotifyToken.invalidate()
    }

    fun setLastfmScrobbleEnabled(value: Boolean) = scrobblingSettings.setLastfmScrobbleEnabled(value)

    fun setLastfmNowPlaying(value: Boolean) = scrobblingSettings.setLastfmNowPlaying(value)

    fun setOutputPcmMode(value: OutputPcmMode) {
        outputPcmMode.value = value
        prefs.edit().putString(KEY_OUTPUT_PCM_MODE, value.name).apply()
    }

    fun setPreferUsbDac(value: Boolean) {
        preferUsbDac.value = value
        prefs.edit().putBoolean(KEY_PREFER_USB_DAC, value).apply()
    }

    fun setBitPerfectMode(value: BitPerfectMode) {
        bitPerfectMode.value = value
        prefs.edit().putString(KEY_BIT_PERFECT_MODE, value.name).apply()
    }

    fun setUsbAllowFixedVolume(value: Boolean) {
        usbAllowFixedVolume.value = value
        prefs.edit().putBoolean(KEY_USB_ALLOW_FIXED_VOLUME, value).apply()
    }

    fun setConvertForLimitedDac(value: Boolean) {
        convertForLimitedDac.value = value
        prefs.edit().putBoolean(KEY_CONVERT_FOR_LIMITED_DAC, value).apply()
    }

    fun setLoudnessOffOnSpeaker(value: Boolean) {
        loudnessOffOnSpeaker.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, value).apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        loudnessNormalization.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_NORMALIZATION, value).apply()
    }

    fun setExportDownloads(value: Boolean) {
        exportDownloads.value = value
        prefs.edit().putBoolean(KEY_EXPORT_DOWNLOADS, value).apply()
    }

    fun setSyncDownloadedPlaylists(value: Boolean) {
        syncDownloadedPlaylists.value = value
        prefs.edit().putBoolean(KEY_SYNC_DOWNLOADED_PLAYLISTS, value).apply()
    }

    fun setLastfmPrimaryArtistOnly(value: Boolean) = scrobblingSettings.setLastfmPrimaryArtistOnly(value)

    fun setScrobbleMinDuration(value: Int) = scrobblingSettings.setScrobbleMinDuration(value)

    fun setScrobbleDelayPercent(value: Float) = scrobblingSettings.setScrobbleDelayPercent(value)

    fun setScrobbleDelaySeconds(value: Int) = scrobblingSettings.setScrobbleDelaySeconds(value)

    fun setListenBrainzEnabled(value: Boolean) = scrobblingSettings.setListenBrainzEnabled(value)

    fun setListenBrainzToken(value: String) = scrobblingSettings.setListenBrainzToken(value)

    fun setListenBrainzPrimaryArtistOnly(value: Boolean) =
        scrobblingSettings.setListenBrainzPrimaryArtistOnly(value)

    fun setDiscordToken(value: String) = discordSettings.setToken(value)

    fun setDiscordAccount(username: String, name: String, avatar: String?) =
        discordSettings.setAccount(username, name, avatar)

    fun setDiscordRpcEnabled(value: Boolean) = discordSettings.setRpcEnabled(value)

    fun setDiscordUseDetails(value: Boolean) = discordSettings.setUseDetails(value)

    fun setDiscordShowAudioQuality(value: Boolean) = discordSettings.setShowAudioQuality(value)

    fun setDiscordAdvancedMode(value: Boolean) = discordSettings.setAdvancedMode(value)

    fun setDiscordStatus(value: String) = discordSettings.setStatus(value)

    fun setDiscordActivityType(value: String) = discordSettings.setActivityType(value)

    fun setDiscordActivityName(value: String) = discordSettings.setActivityName(value)

    fun setDiscordButton1Text(value: String) = discordSettings.setButton1Text(value)

    fun setDiscordButton1Visible(value: Boolean) = discordSettings.setButton1Visible(value)

    fun setDiscordButton2Text(value: String) = discordSettings.setButton2Text(value)

    fun setDiscordButton2Visible(value: Boolean) = discordSettings.setButton2Visible(value)

    fun setDiscordInfoDismissed(value: Boolean) = discordSettings.setInfoDismissed(value)

    fun setReplayGenres(value: Boolean) {
        replayGenres.value = value
        prefs.edit().putBoolean(KEY_REPLAY_GENRES, value).apply()
    }

    fun setFilterNonMusicAudio(value: Boolean) {
        filterNonMusicAudio.value = value
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC_AUDIO, value).apply()
    }

    fun setLocalMusicSort(value: LocalMusicSort) = librarySettings.setLocalMusicSort(value)

    fun setDownloadedMusicSort(value: LocalMusicSort) = librarySettings.setDownloadedMusicSort(value)

    fun setLibrarySort(value: LibrarySort) = librarySettings.setLibrarySort(value)

    fun setDetailSongSort(browseId: String, value: SongSort) = librarySettings.setDetailSongSort(browseId, value)

    fun setLocalMusicViewType(value: LibraryViewType) = librarySettings.setLocalMusicViewType(value)

    fun setDownloadedMusicViewType(value: LibraryViewType) = librarySettings.setDownloadedMusicViewType(value)

    fun setLocalMusicFolderUri(value: String) = librarySettings.setLocalMusicFolderUri(value)

    fun setWebDavUrl(value: String) = remoteStorageSettings.setWebDavUrl(value)

    fun setWebDavUsername(value: String) = remoteStorageSettings.setWebDavUsername(value)

    fun setWebDavPassword(value: String) = remoteStorageSettings.setWebDavPassword(value)

    fun clearWebDav() = remoteStorageSettings.clearWebDav()

    fun setSmbHost(value: String) = remoteStorageSettings.setSmbHost(value)

    fun setSmbShare(value: String) = remoteStorageSettings.setSmbShare(value)

    fun setSmbBasePath(value: String) = remoteStorageSettings.setSmbBasePath(value)

    fun setSmbUsername(value: String) = remoteStorageSettings.setSmbUsername(value)

    fun setSmbPassword(value: String) = remoteStorageSettings.setSmbPassword(value)

    fun clearSmb() = remoteStorageSettings.clearSmb()

    fun togglePinnedPlaylist(browseId: String): Boolean = librarySettings.togglePinnedPlaylist(browseId)

    fun clearDiscordAccount() {
        setDiscordToken("")
        setDiscordAccount("", "", null)
    }

    fun exportPrefs(): Map<String, Any?> {
        if (!this::prefs.isInitialized) return emptyMap()
        return prefs.all.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
    }

    fun importPrefs(values: Map<String, Any?>) {
        if (!this::prefs.isInitialized) return
        val kept = prefs.all.filterKeys { it in SECRETS || it in DEVICE_LOCAL }
        prefs.edit().apply {
            clear()
            val incoming = values.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
            (kept + incoming).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }.apply()
        reload()
    }

    private val SECRETS = setOf(
        KEY_LASTFM_SESSION_KEY,
        KEY_LASTFM_API_KEY,
        KEY_LASTFM_SECRET,
        KEY_LISTENBRAINZ_TOKEN,
        KEY_SPOTIFY_SPDC_TOKEN,
        KEY_PAXSENIX_API_KEY,
    )

    private val DEVICE_LOCAL = setOf(
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
        KEY_LOCAL_MUSIC_FOLDER_URI,
        KEY_LAST_VERSION_CODE,
        KEY_ONBOARDING_COMPLETE,
    )

    const val DEFAULT_CACHE_LIMIT_BYTES = 512L * 1024 * 1024
    const val MAX_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    const val UNLIMITED_CACHE_LIMIT_BYTES = Long.MAX_VALUE

    const val MIN_LYRICS_OFFSET_MS = -5_000
    const val MAX_LYRICS_OFFSET_MS = 5_000

    const val DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS = 5
    const val MIN_UPGRADE_LENGTH_SLACK_SECONDS = 0
    const val MAX_UPGRADE_LENGTH_SLACK_SECONDS = 10

    private const val DEFAULT_PERFORMANCE_REFRESH_RATE = 120

    private fun normalizePerformanceRefreshRate(value: Int): Int =
        value.takeIf { it in 50..240 } ?: DEFAULT_PERFORMANCE_REFRESH_RATE

    fun normalizeSignatureApiUrl(raw: String): String =
        raw.trim().trimEnd('/').take(500)

    private const val KEY_QUALITY_LEGACY = "audio_quality"
    private const val KEY_QUALITY_WIFI = "audio_quality_wifi"
    private const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
    private const val KEY_UPGRADE_LENGTH_SLACK_SECONDS = "upgrade_length_slack_seconds"
    private const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
    private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
    private const val KEY_EXPORT_DOWNLOADS = "export_downloads"
    private const val KEY_SYNC_DOWNLOADED_PLAYLISTS = "sync_downloaded_playlists"
    private const val KEY_CROSSFADE = "crossfade_seconds"
    private const val KEY_SMART_FADE = "smart_fade_enabled"
    private const val KEY_AUTOMIX_PERFORMANCE_MODE = "automix_performance_mode"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_EXCOR_FALLBACK = "excor_fallback"
    private const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
    private const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
    private const val KEY_BIT_PERFECT_MODE = "bit_perfect_mode"
    private const val KEY_USB_ALLOW_FIXED_VOLUME = "usb_allow_fixed_volume"
    private const val KEY_CONVERT_FOR_LIMITED_DAC = "convert_for_limited_dac"
    private const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
    private const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
    private const val KEY_DOLBY_ATMOS = "dolby_atmos"
    private const val KEY_DIRECT_SOURCE_STREAM = "direct_source_stream"
    private const val KEY_SPATIAL_AUDIO = "spatial_audio"
    private const val KEY_EQ_ENABLED = "equalizer_enabled"
    private const val KEY_EQ_MODE = "equalizer_mode"
    private const val KEY_EQ_TONE_X = "equalizer_tone_x"
    private const val KEY_EQ_TONE_Y = "equalizer_tone_y"
    private const val KEY_EQ_FOCUSED = "equalizer_focused"
    private const val KEY_EQ_BALANCE = "equalizer_balance"
    private const val KEY_EQ_BANDS = "equalizer_bands"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_APP_FONT = "app_font"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_LISTEN_TOGETHER_BUTTON = "show_listen_together_button"
    private const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
    private const val KEY_REDUCE_ANIMATION = "reduce_animation"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_DIWALI_GREETING_SHOWN = "diwali_greeting_2026_shown"
    private const val KEY_HIDE_RADIO_TAB = "hide_radio_tab"
    private const val KEY_CAR_MODE = "car_mode"
    private const val KEY_CLASSIC_SEARCH = "classic_search"
    private const val KEY_YOUTUBE_SEARCH = "youtube_search"
    private const val KEY_RADIO_INTRO_SHOWN = "radio_intro_shown"
    private const val KEY_HIDE_PODCASTS_TAB = "hide_podcasts_tab"
    private const val KEY_PODCASTS_INTRO_SHOWN = "podcasts_intro_shown"
    private const val KEY_SUPPORT_PROMPTS_OFF = "support_prompts_off"
    private const val KEY_LAUNCHES = "launch_count"
    private const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
    private const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
    private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
    private const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    private const val KEY_HIDE_STATUS_BAR_IN_PLAYER = "hide_status_bar_in_player"
    private const val KEY_HIDE_SONG_STATUS = "hide_song_status"
    private const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
    private const val KEY_PREFER_MUSIC_ONLY = "prefer_music_only"
    private const val KEY_HIDE_RECENTS = "hide_recents"
    private const val KEY_HIDE_HOME_VIDEOS = "hide_home_videos"
    private const val KEY_HIDE_TOP_PICKS = "hide_top_picks"
    private const val KEY_HIDE_HOME_MOODS = "hide_home_moods"
    private const val KEY_SQUARE_VIDEO_THUMBNAILS = "square_video_thumbnails"
    private const val KEY_SMART_VERSION_ALIGNMENT = "smart_version_alignment"
    private const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
    private const val KEY_DISABLE_BLEND_EFFECTS = "disable_blend_effects"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_GLASS_PERFORMANCE = "glass_performance"
    private const val KEY_PROGRESSIVE_BLUR = "progressive_blur"
    private const val KEY_ANIMATED_CANVAS = "animated_canvas"
    private const val KEY_CANVAS_OVER_CELLULAR = "canvas_over_cellular"
    private const val KEY_SPOTIFY_CANVAS_AUTO_HIDE = "spotify_canvas_auto_hide"
    private const val KEY_PRIORITIZE_SPOTIFY_CANVAS = "prioritize_spotify_canvas"
    private const val KEY_SIGNATURE_API_ENABLED = "signature_api_enabled"
    private const val KEY_SIGNATURE_API_URL = "signature_api_url"
    private const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
    private const val KEY_FULL_BLEED_DETAIL_ARTWORK = "full_bleed_detail_artwork"
    private const val KEY_PLAYER_THEME = "player_theme"
    private const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
    private const val KEY_LAST_PLAYER_SCREEN = "last_player_screen"
    private const val KEY_REPLAY_GENRES = "replay_genres"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
    private const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"

    private const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"

    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}



