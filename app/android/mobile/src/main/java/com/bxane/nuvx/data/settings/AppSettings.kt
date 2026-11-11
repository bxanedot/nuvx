package com.bxane.nuvx.data.settings

import com.bxane.nuvx.data.webdav.update
import com.bxane.nuvx.ui.components.VideoThumbnails
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.bxane.nuvx.BuildConfig
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

    private lateinit var authStore: AuthStore

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

    val lyricsBlur = MutableStateFlow(true)

    val lyricsOffsetMs = MutableStateFlow(0)

    val translationLanguage = MutableStateFlow("")

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

    val syncedLyrics = MutableStateFlow(true)

    val lyricsSources = MutableStateFlow(LyricsSource.offered.toSet())

    val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)

    val prioritizeSyllableSync = MutableStateFlow(true)

    val paxSenixApiKey = MutableStateFlow("")

    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    val showCacheFolder = MutableStateFlow(false)

    val replayGenres = MutableStateFlow(true)

    val filterNonMusicAudio = MutableStateFlow(true)

    val localMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val downloadedMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    val downloadedMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    val detailSongSorts = MutableStateFlow<Map<String, SongSort>>(emptyMap())

    val localMusicFolderUri = MutableStateFlow("")

    val webdavUrl = MutableStateFlow("")
    val webdavUsername = MutableStateFlow("")
    val webdavPassword = MutableStateFlow("")

    val smbHost = MutableStateFlow("")
    val smbShare = MutableStateFlow("")
    val smbBasePath = MutableStateFlow("")
    val smbUsername = MutableStateFlow("")
    val smbPassword = MutableStateFlow("")

    val pinnedPlaylists = MutableStateFlow<List<String>>(emptyList())

    const val MAX_PINNED_PLAYLISTS = 5

    val scrobblingAvailable = true

    val lastfmEnabled = MutableStateFlow(false)
    val lastfmUsername = MutableStateFlow("")
    val lastfmSessionKey = MutableStateFlow("")
    val lastfmApiKey = MutableStateFlow("")
    val lastfmSecret = MutableStateFlow("")
    val lastfmEndpoint = MutableStateFlow("")
    val lastfmScrobbleEnabled = MutableStateFlow(false)
    val lastfmNowPlaying = MutableStateFlow(false)
    val lastfmPrimaryArtistOnly = MutableStateFlow(false)
    val scrobbleMinDuration = MutableStateFlow(30)
    val scrobbleDelayPercent = MutableStateFlow(0.5f)
    val scrobbleDelaySeconds = MutableStateFlow(180)
    val listenBrainzEnabled = MutableStateFlow(false)
    val listenBrainzToken = MutableStateFlow("")
    val listenBrainzPrimaryArtistOnly = MutableStateFlow(false)
    val spotifySpdcToken = MutableStateFlow("")

    val discordToken = MutableStateFlow("")

    val discordUsername = MutableStateFlow("")
    val discordName = MutableStateFlow("")
    val discordAvatar = MutableStateFlow("")

    val discordRpcEnabled = MutableStateFlow(true)

    val discordUseDetails = MutableStateFlow(false)

    val discordShowAudioQuality = MutableStateFlow(true)

    val discordAdvancedMode = MutableStateFlow(false)

    val discordStatus = MutableStateFlow("online")
    val discordActivityType = MutableStateFlow("listening")

    val discordActivityName = MutableStateFlow("")

    val discordButton1Text = MutableStateFlow("")
    val discordButton1Visible = MutableStateFlow(true)
    val discordButton2Text = MutableStateFlow("")
    val discordButton2Visible = MutableStateFlow(true)

    val discordInfoDismissed = MutableStateFlow(false)

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
        this.authStore = authStore
        com.bxane.nuvx.data.spotify.LocalPlaylistStore.init(context)
        com.bxane.nuvx.data.OfflinePlaylists.init(
            context,
            likedTitle = context.getString(com.bxane.nuvx.R.string.auto_liked),
        )
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
        lyricsBlur.value = prefs.getBoolean(KEY_LYRICS_BLUR, true)
        lyricsOffsetMs.value = prefs.getInt(KEY_LYRICS_OFFSET_MS, 0)
            .coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        translationLanguage.value = prefs.getString(KEY_TRANSLATION_LANGUAGE, "").orEmpty()
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
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        lyricsSources.value = readLyricsSources()
        lyricsSourceOrder.value = readLyricsSourceOrder()
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, true)
        paxSenixApiKey.value = prefs.getString(KEY_PAXSENIX_API_KEY, "").orEmpty()
        com.bxane.nuvx.data.lyrics.PaxSenix.setApiKey(paxSenixApiKey.value)
        audioCacheLimitBytes.value = clampCacheLimit(prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES))
        showCacheFolder.value = prefs.getBoolean(KEY_SHOW_CACHE_FOLDER, false)
        lastfmEnabled.value = prefs.getBoolean(KEY_LASTFM_ENABLED, false)
        lastfmUsername.value = prefs.getString(KEY_LASTFM_USERNAME, "").orEmpty()
        lastfmSessionKey.value = prefs.getString(KEY_LASTFM_SESSION_KEY, "").orEmpty()
        lastfmApiKey.value = prefs.getString(KEY_LASTFM_API_KEY, "").orEmpty().ifBlank { BuildConfig.LASTFM_API_KEY }
        lastfmSecret.value = prefs.getString(KEY_LASTFM_SECRET, "").orEmpty().ifBlank { BuildConfig.LASTFM_SECRET }
        lastfmEndpoint.value = prefs.getString(KEY_LASTFM_ENDPOINT, "").orEmpty()
        lastfmScrobbleEnabled.value = prefs.getBoolean(KEY_LASTFM_SCROBBLE_ENABLED, false)
        lastfmNowPlaying.value = prefs.getBoolean(KEY_LASTFM_NOW_PLAYING, false) && lastfmScrobbleEnabled.value
        lastfmPrimaryArtistOnly.value = prefs.getBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, false)
        scrobbleMinDuration.value = prefs.getInt(KEY_SCROBBLE_MIN_DURATION, 30)
        scrobbleDelayPercent.value = prefs.getFloat(KEY_SCROBBLE_DELAY_PERCENT, 0.5f)
        scrobbleDelaySeconds.value = prefs.getInt(KEY_SCROBBLE_DELAY_SECONDS, 180)
        listenBrainzEnabled.value = prefs.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
        listenBrainzToken.value = prefs.getString(KEY_LISTENBRAINZ_TOKEN, "").orEmpty()
        listenBrainzPrimaryArtistOnly.value = prefs.getBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, false)
        spotifySpdcToken.value = prefs.getString(KEY_SPOTIFY_SPDC_TOKEN, "").orEmpty()
        replayGenres.value = prefs.getBoolean(KEY_REPLAY_GENRES, true)
        filterNonMusicAudio.value = prefs.getBoolean(KEY_FILTER_NON_MUSIC_AUDIO, true)
        localMusicSort.value = readLocalMusicSort(KEY_LOCAL_MUSIC_SORT)
        downloadedMusicSort.value = readLocalMusicSort(KEY_DOWNLOADED_MUSIC_SORT)
        localMusicViewType.value = readLibraryViewType(KEY_LOCAL_MUSIC_VIEW_TYPE)
        downloadedMusicViewType.value = readLibraryViewType(KEY_DOWNLOADED_MUSIC_VIEW_TYPE)
        librarySort.value = prefs.getString(KEY_LIBRARY_SORT, null)
            ?.let { saved -> LibrarySort.entries.firstOrNull { it.name == saved } }
            ?: LibrarySort.DEFAULT
        detailSongSorts.value = readDetailSongSorts()
        localMusicFolderUri.value = prefs.getString(KEY_LOCAL_MUSIC_FOLDER_URI, "").orEmpty()
        webdavUrl.value = prefs.getString(KEY_WEBDAV_URL, "").orEmpty()
        webdavUsername.value = prefs.getString(KEY_WEBDAV_USERNAME, "").orEmpty()
        webdavPassword.value = authStore.webdavPassword.orEmpty()
        smbHost.value = prefs.getString(KEY_SMB_HOST, "").orEmpty()
        smbShare.value = prefs.getString(KEY_SMB_SHARE, "").orEmpty()
        smbBasePath.value = prefs.getString(KEY_SMB_BASE_PATH, "").orEmpty()
        smbUsername.value = prefs.getString(KEY_SMB_USERNAME, "").orEmpty()
        smbPassword.value = authStore.smbPassword.orEmpty()
        com.bxane.nuvx.data.smb.SmbAuth.update(
            smbHost.value,
            smbShare.value,
            smbBasePath.value,
            smbUsername.value,
            smbPassword.value,
        )
        com.bxane.nuvx.data.webdav.WebDavAuth.update(
            webdavUrl.value,
            webdavUsername.value,
            webdavPassword.value,
        )
        pinnedPlaylists.value = readPinnedPlaylists()
        discordToken.value = authStore.discordToken.orEmpty()
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordShowAudioQuality.value = prefs.getBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, true)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
        discordInfoDismissed.value = prefs.getBoolean(KEY_DISCORD_INFO_DISMISSED, false)
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

    fun setLyricsBlur(value: Boolean) {
        lyricsBlur.value = value
        prefs.edit().putBoolean(KEY_LYRICS_BLUR, value).apply()
    }

    fun setLyricsOffsetMs(value: Int) {
        val normalized = value.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        if (lyricsOffsetMs.value == normalized) return
        lyricsOffsetMs.value = normalized
        prefs.edit().putInt(KEY_LYRICS_OFFSET_MS, normalized).apply()
    }

    fun setTranslationLanguage(value: String) {
        translationLanguage.value = value
        prefs.edit().putString(KEY_TRANSLATION_LANGUAGE, value).apply()
    }

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.edit().putBoolean(KEY_SYNCED_LYRICS, value).apply()
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        lyricsSources.value = value
        prefs.edit()
            .putString(KEY_LYRICS_SOURCES, value.joinToString(",") { it.name })
            .putString(KEY_LYRICS_SOURCES_SEEN, LyricsSource.offered.joinToString(",") { it.name })
            .apply()
    }

    private fun readLyricsSources(): Set<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCES, null)
            ?: return LyricsSource.offered.toSet()
        val chosen = stored.split(",").toSources()
        val seen = prefs.getString(KEY_LYRICS_SOURCES_SEEN, null)
            ?.split(",")?.toSources()
            ?: LEGACY_SOURCES
        return (chosen + LyricsSource.entries.filter { it !in seen }).filterNot { it.hidden }.toSet()
    }

    private fun List<String>.toSources(): Set<LyricsSource> =
        mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }.toSet()

    private val LEGACY_SOURCES = setOf(
        LyricsSource.LYRICS_PLUS,
        LyricsSource.PAXSENIX,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.SIMP_MUSIC,
        LyricsSource.KUGOU,
        LyricsSource.LRCLIB,
        LyricsSource.MUSIXMATCH,
        LyricsSource.GENIUS,
    )

    fun setLyricsSourceOrder(value: List<LyricsSource>) {
        lyricsSourceOrder.value = value
        prefs.edit().putString(KEY_LYRICS_SOURCE_ORDER, value.joinToString(",") { it.name }).apply()
    }

    private fun readLyricsSourceOrder(): List<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCE_ORDER, null)
            ?: return LyricsSource.offered
        val saved = stored.split(",")
            .mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
        return LyricsSource.ordered(saved)
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, value).apply()
    }

    fun setPaxSenixApiKey(value: String) {
        val normalized = com.bxane.nuvx.data.lyrics.normalizePaxSenixApiKey(value)
        paxSenixApiKey.value = normalized
        prefs.edit().putString(KEY_PAXSENIX_API_KEY, normalized).apply()
        com.bxane.nuvx.data.lyrics.PaxSenix.setApiKey(normalized)
    }

    fun resetLyricsSourceSettings() {
        setLyricsSources(LyricsSource.offered.toSet())
        setLyricsSourceOrder(LyricsSource.offered)
        setPrioritizeSyllableSync(true)
    }

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

    fun setLastfmEnabled(value: Boolean) {
        lastfmEnabled.value = value
        prefs.edit().putBoolean(KEY_LASTFM_ENABLED, value).apply()
    }

    fun setLastfmUsername(value: String) {
        lastfmUsername.value = value
        prefs.edit().putString(KEY_LASTFM_USERNAME, value).apply()
    }

    fun setLastfmSessionKey(value: String) {
        lastfmSessionKey.value = value
        prefs.edit().putString(KEY_LASTFM_SESSION_KEY, value).apply()
    }

    fun setLastfmApiKey(value: String) {
        lastfmApiKey.value = value
        prefs.edit().putString(KEY_LASTFM_API_KEY, value).apply()
    }

    fun setLastfmSecret(value: String) {
        lastfmSecret.value = value
        prefs.edit().putString(KEY_LASTFM_SECRET, value).apply()
    }

    fun setLastfmEndpoint(value: String) {
        lastfmEndpoint.value = value
        prefs.edit().putString(KEY_LASTFM_ENDPOINT, value).apply()
    }

    fun setSpotifySpdcToken(value: String) {
        spotifySpdcToken.value = value
        prefs.edit().putString(KEY_SPOTIFY_SPDC_TOKEN, value).apply()
        SpotifyToken.invalidate()
    }

    fun setLastfmScrobbleEnabled(value: Boolean) {
        lastfmScrobbleEnabled.value = value
        if (!value) lastfmNowPlaying.value = false
        prefs.edit()
            .putBoolean(KEY_LASTFM_SCROBBLE_ENABLED, value)
            .putBoolean(KEY_LASTFM_NOW_PLAYING, if (value) lastfmNowPlaying.value else false)
            .apply()
    }

    fun setLastfmNowPlaying(value: Boolean) {
        if (!lastfmScrobbleEnabled.value && value) return
        lastfmNowPlaying.value = value
        prefs.edit().putBoolean(KEY_LASTFM_NOW_PLAYING, value).apply()
    }

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

    fun setLastfmPrimaryArtistOnly(value: Boolean) {
        lastfmPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LASTFM_PRIMARY_ARTIST_ONLY, value).apply()
    }

    fun setScrobbleMinDuration(value: Int) {
        scrobbleMinDuration.value = value
        prefs.edit().putInt(KEY_SCROBBLE_MIN_DURATION, value).apply()
    }

    fun setScrobbleDelayPercent(value: Float) {
        scrobbleDelayPercent.value = value
        prefs.edit().putFloat(KEY_SCROBBLE_DELAY_PERCENT, value).apply()
    }

    fun setScrobbleDelaySeconds(value: Int) {
        scrobbleDelaySeconds.value = value
        prefs.edit().putInt(KEY_SCROBBLE_DELAY_SECONDS, value).apply()
    }

    fun setListenBrainzEnabled(value: Boolean) {
        listenBrainzEnabled.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_ENABLED, value).apply()
    }

    fun setListenBrainzToken(value: String) {
        listenBrainzToken.value = value
        prefs.edit().putString(KEY_LISTENBRAINZ_TOKEN, value).apply()
    }

    fun setListenBrainzPrimaryArtistOnly(value: Boolean) {
        listenBrainzPrimaryArtistOnly.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY, value).apply()
    }

    fun setDiscordToken(value: String) {
        discordToken.value = value
        authStore.discordToken = value.ifEmpty { null }
    }

    fun setDiscordAccount(username: String, name: String, avatar: String?) {
        discordUsername.value = username
        discordName.value = name
        discordAvatar.value = avatar.orEmpty()
        prefs.edit()
            .putString(KEY_DISCORD_USERNAME, username)
            .putString(KEY_DISCORD_NAME, name)
            .putString(KEY_DISCORD_AVATAR, avatar.orEmpty())
            .apply()
    }

    fun setDiscordRpcEnabled(value: Boolean) {
        discordRpcEnabled.value = value
        prefs.edit().putBoolean(KEY_DISCORD_RPC_ENABLED, value).apply()
    }

    fun setDiscordUseDetails(value: Boolean) {
        discordUseDetails.value = value
        prefs.edit().putBoolean(KEY_DISCORD_USE_DETAILS, value).apply()
    }

    fun setDiscordShowAudioQuality(value: Boolean) {
        discordShowAudioQuality.value = value
        prefs.edit().putBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, value).apply()
    }

    fun setDiscordAdvancedMode(value: Boolean) {
        discordAdvancedMode.value = value
        prefs.edit().putBoolean(KEY_DISCORD_ADVANCED_MODE, value).apply()
    }

    fun setDiscordStatus(value: String) {
        discordStatus.value = value
        prefs.edit().putString(KEY_DISCORD_STATUS, value).apply()
    }

    fun setDiscordActivityType(value: String) {
        discordActivityType.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_TYPE, value).apply()
    }

    fun setDiscordActivityName(value: String) {
        discordActivityName.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_NAME, value).apply()
    }

    fun setDiscordButton1Text(value: String) {
        discordButton1Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_1_TEXT, value).apply()
    }

    fun setDiscordButton1Visible(value: Boolean) {
        discordButton1Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, value).apply()
    }

    fun setDiscordButton2Text(value: String) {
        discordButton2Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_2_TEXT, value).apply()
    }

    fun setDiscordButton2Visible(value: Boolean) {
        discordButton2Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, value).apply()
    }

    fun setDiscordInfoDismissed(value: Boolean) {
        discordInfoDismissed.value = value
        prefs.edit().putBoolean(KEY_DISCORD_INFO_DISMISSED, value).apply()
    }

    fun setReplayGenres(value: Boolean) {
        replayGenres.value = value
        prefs.edit().putBoolean(KEY_REPLAY_GENRES, value).apply()
    }

    fun setFilterNonMusicAudio(value: Boolean) {
        filterNonMusicAudio.value = value
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC_AUDIO, value).apply()
    }

    fun setLocalMusicSort(value: LocalMusicSort) {
        localMusicSort.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_SORT, value.name).apply()
    }

    fun setDownloadedMusicSort(value: LocalMusicSort) {
        downloadedMusicSort.value = value
        prefs.edit().putString(KEY_DOWNLOADED_MUSIC_SORT, value.name).apply()
    }

    fun setLibrarySort(value: LibrarySort) {
        librarySort.value = value
        prefs.edit().putString(KEY_LIBRARY_SORT, value.name).apply()
    }

    fun setDetailSongSort(browseId: String, value: SongSort) {
        detailSongSorts.value = detailSongSorts.value + (browseId to value)
        prefs.edit().putString(
            KEY_DETAIL_SONG_SORTS,
            detailSongSorts.value.entries.joinToString(",") { (id, sort) -> "$id=${sort.name}" },
        ).apply()
    }

    fun setLocalMusicViewType(value: LibraryViewType) {
        localMusicViewType.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_VIEW_TYPE, value.name).apply()
    }

    fun setDownloadedMusicViewType(value: LibraryViewType) {
        downloadedMusicViewType.value = value
        prefs.edit().putString(KEY_DOWNLOADED_MUSIC_VIEW_TYPE, value.name).apply()
    }

    fun setLocalMusicFolderUri(value: String) {
        localMusicFolderUri.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_FOLDER_URI, value).apply()
    }

    fun setWebDavUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        webdavUrl.value = normalized
        prefs.edit().putString(KEY_WEBDAV_URL, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavUsername(value: String) {
        val normalized = value.trim()
        webdavUsername.value = normalized
        prefs.edit().putString(KEY_WEBDAV_USERNAME, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavPassword(value: String) {
        webdavPassword.value = value
        authStore.webdavPassword = value.ifEmpty { null }
        publishWebDavAuth()
    }

    fun clearWebDav() {
        setWebDavUrl("")
        setWebDavUsername("")
        setWebDavPassword("")
    }

    fun setSmbHost(value: String) {
        val normalized = value.trim()
        smbHost.value = normalized
        prefs.edit().putString(KEY_SMB_HOST, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbShare(value: String) {
        val normalized = value.trim().trim('/')
        smbShare.value = normalized
        prefs.edit().putString(KEY_SMB_SHARE, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbBasePath(value: String) {
        val normalized = value.trim().trim('/')
        smbBasePath.value = normalized
        prefs.edit().putString(KEY_SMB_BASE_PATH, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbUsername(value: String) {
        val normalized = value.trim()
        smbUsername.value = normalized
        prefs.edit().putString(KEY_SMB_USERNAME, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbPassword(value: String) {
        smbPassword.value = value
        authStore.smbPassword = value.ifEmpty { null }
        publishSmbAuth()
    }

    fun clearSmb() {
        setSmbHost("")
        setSmbShare("")
        setSmbBasePath("")
        setSmbUsername("")
        setSmbPassword("")
    }

    private fun publishSmbAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.bxane.nuvx.data.smb.SmbAuth.update(
            smbHost.value,
            smbShare.value,
            smbBasePath.value,
            smbUsername.value,
            smbPassword.value,
        )
    }

    private fun publishWebDavAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.bxane.nuvx.data.webdav.WebDavAuth.update(
            webdavUrl.value,
            webdavUsername.value,
            webdavPassword.value,
        )
    }

    private fun readLocalMusicSort(key: String): LocalMusicSort =
        prefs.getString(key, null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } }
            ?: LocalMusicSort.TITLE_ASC

    private fun readLibraryViewType(key: String): LibraryViewType =
        prefs.getString(key, null)
            ?.let { saved -> LibraryViewType.entries.firstOrNull { it.name == saved } }
            ?: LibraryViewType.LIST

    fun togglePinnedPlaylist(browseId: String): Boolean {
        val current = pinnedPlaylists.value
        val updated = when {
            browseId in current -> current - browseId
            current.size >= MAX_PINNED_PLAYLISTS -> return false
            else -> current + browseId
        }
        pinnedPlaylists.value = updated
        prefs.edit().putString(KEY_PINNED_PLAYLISTS, updated.joinToString(",")).apply()
        return browseId in updated
    }

    private fun readDetailSongSorts(): Map<String, SongSort> =
        prefs.getString(KEY_DETAIL_SONG_SORTS, null)
            ?.split(",")
            ?.mapNotNull { entry ->
                val id = entry.substringBefore('=', "")
                val sort = SongSort.entries.firstOrNull { it.name == entry.substringAfter('=', "") }
                if (id.isBlank() || sort == null) null else id to sort
            }
            ?.toMap()
            ?: emptyMap()

    private fun readPinnedPlaylists(): List<String> {
        val stored = prefs.getString(KEY_PINNED_PLAYLISTS, null) ?: return emptyList()
        return stored.split(",").filter { it.isNotBlank() }
    }

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
    private const val KEY_LYRICS_BLUR = "lyrics_blur"
    private const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
    private const val KEY_TRANSLATION_LANGUAGE = "translation_language"
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
    private const val KEY_SYNCED_LYRICS = "synced_lyrics"
    private const val KEY_LYRICS_SOURCES = "lyrics_sources"
    private const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
    private const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
    private const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    private const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
    private const val KEY_REPLAY_GENRES = "replay_genres"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
    private const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"
    private const val KEY_LOCAL_MUSIC_SORT = "local_music_sort"
    private const val KEY_DOWNLOADED_MUSIC_SORT = "downloaded_music_sort"
    private const val KEY_LIBRARY_SORT = "library_sort"
    private const val KEY_DETAIL_SONG_SORTS = "detail_song_sorts"
    private const val KEY_LOCAL_MUSIC_VIEW_TYPE = "local_music_view_type"
    private const val KEY_DOWNLOADED_MUSIC_VIEW_TYPE = "downloaded_music_view_type"
    private const val KEY_LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USERNAME = "webdav_username"
    private const val KEY_SMB_HOST = "smb_host"
    private const val KEY_SMB_SHARE = "smb_share"
    private const val KEY_SMB_BASE_PATH = "smb_base_path"
    private const val KEY_SMB_USERNAME = "smb_username"
    private const val KEY_PINNED_PLAYLISTS = "pinned_playlists"

    private const val KEY_LASTFM_ENABLED = "lastfm_enabled"
    private const val KEY_LASTFM_USERNAME = "lastfm_username"
    private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"
    private const val KEY_LASTFM_ENDPOINT = "lastfm_endpoint"
    private const val KEY_LASTFM_SCROBBLE_ENABLED = "lastfm_scrobble_enabled"
    private const val KEY_LASTFM_NOW_PLAYING = "lastfm_now_playing"
    private const val KEY_LASTFM_PRIMARY_ARTIST_ONLY = "lastfm_primary_artist_only"
    private const val KEY_SCROBBLE_MIN_DURATION = "scrobble_min_duration"
    private const val KEY_SCROBBLE_DELAY_PERCENT = "scrobble_delay_percent"
    private const val KEY_SCROBBLE_DELAY_SECONDS = "scrobble_delay_seconds"
    private const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
    private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
    private const val KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY = "listenbrainz_primary_artist_only"
    private const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"

    private const val KEY_DISCORD_USERNAME = "discord_username"
    private const val KEY_DISCORD_NAME = "discord_name"
    private const val KEY_DISCORD_AVATAR = "discord_avatar"
    private const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
    private const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
    private const val KEY_DISCORD_SHOW_AUDIO_QUALITY = "discord_show_audio_quality"
    private const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
    private const val KEY_DISCORD_STATUS = "discord_status"
    private const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
    private const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
    private const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
    private const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
    private const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
    private const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
    private const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}



