package com.bxane.nuvx.data.settings

import com.bxane.nuvx.data.webdav.update
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.data.canvas.SpotifyToken
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.sources.SourceKind
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
    private val downloadSettings = DownloadSettingsController()
    private val homeFeedSettings = HomeFeedSettingsController()
    private val playerPresentationSettings = PlayerPresentationSettingsController()
    private val audioCacheSettings = AudioCacheSettingsController(
        DEFAULT_CACHE_LIMIT_BYTES,
        MAX_CACHE_LIMIT_BYTES,
        UNLIMITED_CACHE_LIMIT_BYTES,
    )
    private val audioQualitySettings = AudioQualitySettingsController(
        DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS,
        MIN_UPGRADE_LENGTH_SLACK_SECONDS,
        MAX_UPGRADE_LENGTH_SLACK_SECONDS,
    )
    private val audioOutputSettings = AudioOutputSettingsController()
    private val equalizerSettings = EqualizerSettingsController()
    private val transitionSettings = PlaybackTransitionSettingsController()
    private val appearanceSettings = AppearanceSettingsController()

    val audioQualityWifi = audioQualitySettings.audioQualityWifi
    val audioQualityCellular = audioQualitySettings.audioQualityCellular

    val upgradeLengthSlackSeconds = audioQualitySettings.upgradeLengthSlackSeconds

    val downloadQuality = downloadSettings.downloadQuality

    val wifiOnlyDownloads = downloadSettings.wifiOnlyDownloads

    val exportDownloads = downloadSettings.exportDownloads

    val syncDownloadedPlaylists = downloadSettings.syncDownloadedPlaylists

    val meteredConnection = MutableStateFlow<Boolean?>(null)

    val crossfadeSeconds = transitionSettings.crossfadeSeconds

    val smartFadeEnabled = transitionSettings.smartFadeEnabled

    val automixPerformanceMode = transitionSettings.automixPerformanceMode
    val skipSilence = transitionSettings.skipSilence

    val excorFallback = audioOutputSettings.excorFallback

    val outputPcmMode = audioOutputSettings.outputPcmMode

    val preferUsbDac = audioOutputSettings.preferUsbDac

    val bitPerfectMode = audioOutputSettings.bitPerfectMode

    val usbAllowFixedVolume = audioOutputSettings.usbAllowFixedVolume

    val convertForLimitedDac = audioOutputSettings.convertForLimitedDac

    val loudnessNormalization = audioOutputSettings.loudnessNormalization

    val loudnessOffOnSpeaker = audioOutputSettings.loudnessOffOnSpeaker

    val dolbyAtmos = audioOutputSettings.dolbyAtmos

    val directSourceStream = audioOutputSettings.directSourceStream

    val spatialAudio = audioOutputSettings.spatialAudio

    val equalizerEnabled = equalizerSettings.enabled

    val equalizerMode = equalizerSettings.mode

    val equalizerToneX = equalizerSettings.toneX

    val equalizerToneY = equalizerSettings.toneY

    val equalizerFocused = equalizerSettings.focused

    val equalizerBalance = equalizerSettings.balance

    val equalizerBands = equalizerSettings.bands

    val equalizerPreset = equalizerSettings.preset

    val playbackSpeed = MutableStateFlow(1.0f)
    val themeMode = appearanceSettings.themeMode
    val appFont = appearanceSettings.appFont

    val autoplay = MutableStateFlow(true)

    val shuffleEnabled = MutableStateFlow(false)

    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

    val showNerdStats = MutableStateFlow(false)

    val showListenTogetherButton = MutableStateFlow(true)

    val reduceAnimation = appearanceSettings.reduceAnimation

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

    val highPerformanceMode = appearanceSettings.highPerformanceMode

    val performanceRefreshRate = appearanceSettings.performanceRefreshRate

    val stopOnTaskRemoved = MutableStateFlow(false)

    val hideVolumeBar = MutableStateFlow(false)

    val hideStatusBarInPlayer = MutableStateFlow(false)

    val hideSongStatus = MutableStateFlow(false)

    val showQueueHistory = MutableStateFlow(false)

    val swipeToPlayNext = MutableStateFlow(false)

    val dontRepeatSuggestions = MutableStateFlow(false)

    val preferMusicOnly = MutableStateFlow(false)

    val hideRecents = homeFeedSettings.hideRecents

    val hideHomeVideos = homeFeedSettings.hideHomeVideos

    val hideTopPicks = homeFeedSettings.hideTopPicks

    val hideHomeMoods = homeFeedSettings.hideHomeMoods

    val squareVideoThumbnails = homeFeedSettings.squareVideoThumbnails

    val smartVersionAlignment = appearanceSettings.smartVersionAlignment

    val reduceDynamicBlur = appearanceSettings.reduceDynamicBlur

    val disableBlendEffects = appearanceSettings.disableBlendEffects

    val liquidGlass = appearanceSettings.liquidGlass

    val glassPerformance = appearanceSettings.glassPerformance

    val progressiveBlur = appearanceSettings.progressiveBlur

    val lyricsBlur = lyricsSettings.lyricsBlur

    val lyricsOffsetMs = lyricsSettings.lyricsOffsetMs

    val translationLanguage = lyricsSettings.translationLanguage

    val animatedCanvas = playerPresentationSettings.animatedCanvas

    val canvasOverCellular = playerPresentationSettings.canvasOverCellular

    val spotifyCanvasAutoHide = playerPresentationSettings.spotifyCanvasAutoHide

    val prioritizeSpotifyCanvas = playerPresentationSettings.prioritizeSpotifyCanvas

    val signatureApiEnabled = playerPresentationSettings.signatureApiEnabled
    val signatureApiUrl = playerPresentationSettings.signatureApiUrl

    val fullBleedArtwork = playerPresentationSettings.fullBleedArtwork

    val fullBleedDetailArtwork = playerPresentationSettings.fullBleedDetailArtwork

    val playerTheme = playerPresentationSettings.playerTheme

    val lastPlayerScreen = playerPresentationSettings.lastPlayerScreen

    val syncedLyrics = lyricsSettings.syncedLyrics

    val lyricsSources = lyricsSettings.lyricsSources

    val lyricsSourceOrder = lyricsSettings.lyricsSourceOrder

    val prioritizeSyllableSync = lyricsSettings.prioritizeSyllableSync

    val paxSenixApiKey = lyricsSettings.paxSenixApiKey

    val audioCacheLimitBytes = audioCacheSettings.cacheLimitBytes

    val showCacheFolder = audioCacheSettings.showCacheFolder

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
        downloadSettings.initialize(prefs)
        homeFeedSettings.initialize(prefs)
        playerPresentationSettings.initialize(prefs)
        audioCacheSettings.initialize(prefs)
        audioQualitySettings.initialize(prefs)
        audioOutputSettings.initialize(prefs)
        equalizerSettings.initialize(prefs)
        transitionSettings.initialize(prefs)
        appearanceSettings.initialize(prefs)
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
        audioQualitySettings.read()
        downloadSettings.read()
        transitionSettings.read()
        audioOutputSettings.read()
        equalizerSettings.read()
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        appearanceSettings.readThemeAndFont()
        autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        shuffleEnabled.value = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        repeatMode.value = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        showListenTogetherButton.value = prefs.getBoolean(KEY_LISTEN_TOGETHER_BUTTON, true)
        appearanceSettings.readReduceAnimation()
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
        appearanceSettings.readPerformanceMode()
        stopOnTaskRemoved.value = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, false)
        hideVolumeBar.value = prefs.getBoolean(KEY_HIDE_VOLUME_BAR, false)
        hideStatusBarInPlayer.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, false)
        hideSongStatus.value = prefs.getBoolean(KEY_HIDE_SONG_STATUS, false)
        showQueueHistory.value = prefs.getBoolean(KEY_SHOW_QUEUE_HISTORY, false)
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        dontRepeatSuggestions.value = prefs.getBoolean(KEY_DONT_REPEAT_SUGGESTIONS, false)
        preferMusicOnly.value = prefs.getBoolean(KEY_PREFER_MUSIC_ONLY, false)
        homeFeedSettings.read()
        appearanceSettings.readVisualEffects()
        lyricsSettings.readPresentationPreferences()
        appearanceSettings.applyPerformanceModeOverrides()
        playerPresentationSettings.read()
        lyricsSettings.readProviderPreferences()
        audioCacheSettings.read()
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

    fun setAudioQualityWifi(value: AudioQuality) = audioQualitySettings.setAudioQualityWifi(value)

    fun setAudioQualityCellular(value: AudioQuality) = audioQualitySettings.setAudioQualityCellular(value)

    fun setUpgradeLengthSlackSeconds(value: Int) = audioQualitySettings.setUpgradeLengthSlackSeconds(value)

    fun setDownloadQuality(value: DownloadQuality) = downloadSettings.setDownloadQuality(value)

    fun setWifiOnlyDownloads(value: Boolean) = downloadSettings.setWifiOnlyDownloads(value)

    fun setCrossfadeSeconds(value: Int) = transitionSettings.setCrossfadeSeconds(value)

    fun setSmartFadeEnabled(value: Boolean) = transitionSettings.setSmartFadeEnabled(value)

    fun setAutomixPerformanceMode(value: AutomixPerformanceMode) = transitionSettings.setAutomixPerformanceMode(value)

    fun setExcorFallback(value: Boolean) = audioOutputSettings.setExcorFallback(value)

    fun setSkipSilence(value: Boolean) = transitionSettings.setSkipSilence(value)

    fun setDolbyAtmos(value: Boolean) = audioOutputSettings.setDolbyAtmos(value)

    fun setDirectSourceStream(value: Boolean) = audioOutputSettings.setDirectSourceStream(value)

    fun setSpatialAudio(value: Boolean) = audioOutputSettings.setSpatialAudio(value)

    fun setEqualizerEnabled(value: Boolean) = equalizerSettings.setEnabled(value)

    fun setEqualizerMode(value: EqualizerMode) = equalizerSettings.setMode(value)

    fun setEqualizerTone(x: Int, y: Int) = equalizerSettings.setTone(x, y)

    fun setEqualizerFocused(value: Boolean) = equalizerSettings.setFocused(value)

    fun setEqualizerBalance(value: Float) = equalizerSettings.setBalance(value)

    fun setEqualizerBands(values: List<Float>) = equalizerSettings.setBands(values)

    fun setEqualizerPreset(preset: EqualizerPreset) = equalizerSettings.setPreset(preset)

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

    fun setThemeMode(value: ThemeMode) = appearanceSettings.setThemeMode(value)

    fun setAppFont(value: AppFont) = appearanceSettings.setAppFont(value)

    fun setReduceAnimation(value: Boolean) = appearanceSettings.setReduceAnimation(value)

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

    fun setHideRecents(value: Boolean) = homeFeedSettings.setHideRecents(value)

    fun setHideHomeVideos(value: Boolean) = homeFeedSettings.setHideHomeVideos(value)

    fun setHideTopPicks(value: Boolean) = homeFeedSettings.setHideTopPicks(value)

    fun setHideHomeMoods(value: Boolean) = homeFeedSettings.setHideHomeMoods(value)

    fun setSquareVideoThumbnails(value: Boolean) = homeFeedSettings.setSquareVideoThumbnails(value)

    fun setSmartVersionAlignment(value: Boolean) = appearanceSettings.setSmartVersionAlignment(value)

    fun setDisableBlendEffects(value: Boolean) = appearanceSettings.setDisableBlendEffects(value)

    fun setReduceDynamicBlur(value: Boolean) = appearanceSettings.setReduceDynamicBlur(value)

    fun setLiquidGlass(value: Boolean) = appearanceSettings.setLiquidGlass(value)

    fun setGlassPerformance(value: Boolean) = appearanceSettings.setGlassPerformance(value)

    fun setProgressiveBlur(value: Boolean) = appearanceSettings.setProgressiveBlur(value)

    fun setHighPerformanceMode(value: Boolean) = appearanceSettings.setHighPerformanceMode(value)

    fun setPerformanceRefreshRate(value: Int) = appearanceSettings.setPerformanceRefreshRate(value)

    fun setLyricsBlur(value: Boolean) = lyricsSettings.setLyricsBlur(value)

    fun setLyricsOffsetMs(value: Int) = lyricsSettings.setLyricsOffsetMs(value)

    fun setTranslationLanguage(value: String) = lyricsSettings.setTranslationLanguage(value)

    fun setSyncedLyrics(value: Boolean) = lyricsSettings.setSyncedLyrics(value)

    fun setLyricsSources(value: Set<LyricsSource>) = lyricsSettings.setLyricsSources(value)

    fun setLyricsSourceOrder(value: List<LyricsSource>) = lyricsSettings.setLyricsSourceOrder(value)

    fun setPrioritizeSyllableSync(value: Boolean) = lyricsSettings.setPrioritizeSyllableSync(value)

    fun setPaxSenixApiKey(value: String) = lyricsSettings.setPaxSenixApiKey(value)

    fun resetLyricsSourceSettings() = lyricsSettings.resetLyricsSourceSettings()

    fun setAnimatedCanvas(value: Boolean) = playerPresentationSettings.setAnimatedCanvas(value)

    fun setCanvasOverCellular(value: Boolean) = playerPresentationSettings.setCanvasOverCellular(value)

    fun setSpotifyCanvasAutoHide(value: Boolean) = playerPresentationSettings.setSpotifyCanvasAutoHide(value)

    fun setPrioritizeSpotifyCanvas(value: Boolean) = playerPresentationSettings.setPrioritizeSpotifyCanvas(value)

    fun setSignatureApiEnabled(value: Boolean) = playerPresentationSettings.setSignatureApiEnabled(value)

    fun setSignatureApiUrl(value: String) = playerPresentationSettings.setSignatureApiUrl(value)

    fun setFullBleedArtwork(value: Boolean) = playerPresentationSettings.setFullBleedArtwork(value)

    fun setFullBleedDetailArtwork(value: Boolean) = playerPresentationSettings.setFullBleedDetailArtwork(value)

    fun setPlayerTheme(value: PlayerTheme) = playerPresentationSettings.setPlayerTheme(value)

    fun setLastPlayerScreen(value: LastPlayerScreen) = playerPresentationSettings.setLastPlayerScreen(value)

    fun setAudioCacheLimitBytes(value: Long) = audioCacheSettings.setCacheLimitBytes(value)

    fun setShowCacheFolder(value: Boolean) = audioCacheSettings.setShowCacheFolder(value)

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

    fun setOutputPcmMode(value: OutputPcmMode) = audioOutputSettings.setOutputPcmMode(value)

    fun setPreferUsbDac(value: Boolean) = audioOutputSettings.setPreferUsbDac(value)

    fun setBitPerfectMode(value: BitPerfectMode) = audioOutputSettings.setBitPerfectMode(value)

    fun setUsbAllowFixedVolume(value: Boolean) = audioOutputSettings.setUsbAllowFixedVolume(value)

    fun setConvertForLimitedDac(value: Boolean) = audioOutputSettings.setConvertForLimitedDac(value)

    fun setLoudnessOffOnSpeaker(value: Boolean) = audioOutputSettings.setLoudnessOffOnSpeaker(value)

    fun setLoudnessNormalization(value: Boolean) = audioOutputSettings.setLoudnessNormalization(value)

    fun setExportDownloads(value: Boolean) = downloadSettings.setExportDownloads(value)

    fun setSyncDownloadedPlaylists(value: Boolean) = downloadSettings.setSyncDownloadedPlaylists(value)

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

    fun normalizeSignatureApiUrl(raw: String): String = playerPresentationSettings.normalizeSignatureApiUrl(raw)

    private const val KEY_SPEED = "playback_speed"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_LISTEN_TOGETHER_BUTTON = "show_listen_together_button"
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
    private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
    private const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    private const val KEY_HIDE_STATUS_BAR_IN_PLAYER = "hide_status_bar_in_player"
    private const val KEY_HIDE_SONG_STATUS = "hide_song_status"
    private const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
    private const val KEY_PREFER_MUSIC_ONLY = "prefer_music_only"
    private const val KEY_REPLAY_GENRES = "replay_genres"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"

    private const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"

    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}



