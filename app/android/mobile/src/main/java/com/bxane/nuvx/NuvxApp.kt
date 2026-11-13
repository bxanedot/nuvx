package com.bxane.nuvx

import com.bxane.nuvx.ui.icons.SfIcons
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.bxane.nuvx.data.listentogether.ServerConnectionState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bxane.nuvx.auth.WebSessionMode
import com.bxane.nuvx.auth.YtMusicLoginScreen
import com.bxane.nuvx.data.AppUpdateChecker
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.listentogether.JamInviteLink
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.withoutVideos
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.EntityType
import com.bxane.nuvx.data.model.SearchHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bxane.nuvx.data.model.durationMillis
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.BitPerfectMode
import com.bxane.nuvx.data.settings.LibrarySort
import com.bxane.nuvx.ui.components.AccountProfileSelector
import com.bxane.nuvx.ui.components.PodcastsSymbol
import com.bxane.nuvx.ui.components.DacVolumeHud
import com.bxane.nuvx.ui.components.DacVolumeKeys
import com.bxane.nuvx.ui.components.LocalPlayingBarsMotion
import com.bxane.nuvx.ui.components.VolumeHud
import com.bxane.nuvx.ui.navigation.AppDestination
import com.bxane.nuvx.ui.navigation.AppRoute
import com.bxane.nuvx.ui.screens.EqualizerPresetPage
import com.bxane.nuvx.ui.screens.SettingsChoicePage
import com.bxane.nuvx.ui.components.SpotifyImportAlert
import com.bxane.nuvx.ui.screens.WearOsScreen
import com.bxane.nuvx.ui.screens.AccountAndScrobblingScreen
import com.bxane.nuvx.ui.screens.DiscordDialog
import com.bxane.nuvx.ui.screens.DiscordDialogHost
import com.bxane.nuvx.ui.screens.DiscordScreen
import com.bxane.nuvx.ui.screens.EqualizerScreen
import com.bxane.nuvx.ui.screens.HistoryScreen
import com.bxane.nuvx.ui.screens.LibraryReplayEntry
import com.bxane.nuvx.ui.screens.libraryDeviceItems
import com.bxane.nuvx.ui.screens.SPOTIFY_BROWSE_ID
import com.bxane.nuvx.ui.screens.libraryLinks
import com.bxane.nuvx.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.bxane.nuvx.ui.screens.ListenTogetherScreen
import com.bxane.nuvx.ui.screens.PartyServerEditor
import com.bxane.nuvx.ui.screens.SignatureApiEditor
import com.bxane.nuvx.ui.screens.SettingsScreen
import com.bxane.nuvx.ui.screens.SourcesScreen
import com.bxane.nuvx.ui.screens.SpotifyLibraryScreen
import com.bxane.nuvx.data.spotify.SPOTIFY_PAGE_PREFIX
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.MusicLink
import com.bxane.nuvx.playback.OriginalVersion
import com.bxane.nuvx.playback.PlayerDeepLink
import com.bxane.nuvx.playback.PlaybackVersionSwitcher
import com.bxane.nuvx.playback.PlaybackQueueActions
import com.bxane.nuvx.playback.QueueCoordinator
import com.bxane.nuvx.playback.QueueShuffle
import com.bxane.nuvx.playback.QueueSource
import com.bxane.nuvx.playback.cast.CastController
import com.bxane.nuvx.playback.autoplayEnabledFor
import com.bxane.nuvx.playback.beginRadioQueue
import com.bxane.nuvx.playback.commitRadioQueue
import com.bxane.nuvx.playback.hasYouTubeOriginal
import com.bxane.nuvx.playback.playSongs
import com.bxane.nuvx.playback.contextEntryKey
import com.bxane.nuvx.playback.toggleAutoplay
import com.bxane.nuvx.playback.toggleShuffle
import com.bxane.nuvx.playback.upgradeQuality
import com.bxane.nuvx.playback.revertToOriginal
import com.bxane.nuvx.download.DownloadStore
import com.bxane.nuvx.download.MediaTagger
import com.bxane.nuvx.download.DownloadTarget
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.ui.components.BrowseActionsMenu
import com.bxane.nuvx.ui.components.BrowseTarget
import com.bxane.nuvx.ui.components.ConfirmationAlert
import com.bxane.nuvx.ui.components.DownloadManagerPage
import com.bxane.nuvx.ui.components.PlaylistPickerSheet
import com.bxane.nuvx.ui.components.ReorderPlaylistPage
import com.bxane.nuvx.ui.components.LongPressOrigin
import com.bxane.nuvx.ui.components.MenuAnchor
import com.bxane.nuvx.ui.components.menuAnchor
import com.bxane.nuvx.ui.components.IosMenuPopover
import com.bxane.nuvx.ui.components.SongActionsMenu
import com.bxane.nuvx.ui.components.HeldContextMenu
import com.bxane.nuvx.ui.components.HeldItem
import androidx.media3.session.MediaController
import com.bxane.nuvx.playback.QualityUpgrade
import com.bxane.nuvx.playback.rememberMediaController
import com.bxane.nuvx.playback.rememberPlayerState
import com.bxane.nuvx.playback.setQueueDragActive
import com.bxane.nuvx.ui.MainViewModel
import com.bxane.nuvx.ui.MainMusicLinkHandler
import com.bxane.nuvx.ui.MainBrowseOpener
import com.bxane.nuvx.ui.MainPlaybackStarter
import com.bxane.nuvx.ui.MainPlaybackSourceNavigator
import com.bxane.nuvx.ui.SearchSource
import com.bxane.nuvx.ui.components.BottomFadeScrim
import com.bxane.nuvx.ui.components.TopFadeBlur
import com.bxane.nuvx.ui.components.FloatingBarsTapGuard
import com.bxane.nuvx.ui.components.BottomTab
import com.bxane.nuvx.ui.components.FLOATING_BAR_MAX_WIDTH
import com.bxane.nuvx.ui.components.FloatingBottomBar
import com.bxane.nuvx.ui.components.SearchDock
import com.bxane.nuvx.ui.components.GlassNavBar
import com.bxane.nuvx.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.bxane.nuvx.ui.components.FrostedTopBar
import com.bxane.nuvx.ui.components.LocalLiquidGlassEnabled
import com.bxane.nuvx.ui.components.backdrop.backdrops.LayerBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.layerBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.bxane.nuvx.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.bxane.nuvx.ui.components.isGlassSupported
import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.ui.SourceConfigurationDialogs
import com.bxane.nuvx.ui.AddonPlaybackAlerts
import com.bxane.nuvx.ui.components.ScrobbleLoginDialogs
import com.bxane.nuvx.ui.components.MiniPlayer
import com.bxane.nuvx.ui.components.QueueActionNotice
import com.bxane.nuvx.ui.components.QueueActionNoticeHost
import com.bxane.nuvx.ui.components.TopBarAccountButton
import com.bxane.nuvx.ui.screens.SegmentedControl
import com.bxane.nuvx.ui.components.SearchField
import com.bxane.nuvx.sharedui.resources.Res as SharedRes
import com.bxane.nuvx.sharedui.resources.search_hint
import com.bxane.nuvx.sharedui.resources.search_addons_hint
import com.bxane.nuvx.sharedui.resources.search_library_hint
import com.bxane.nuvx.ui.components.TopBarDownloadButton
import com.bxane.nuvx.ui.components.topBarContentPadding
import com.bxane.nuvx.ui.components.topBarHeight
import com.bxane.nuvx.ui.components.AppLanguageDialog
import com.bxane.nuvx.ui.components.TranslationLanguageDialog
import com.bxane.nuvx.ui.components.LyricsSourcesDialog
import com.bxane.nuvx.ui.components.UpdateAvailableDialog
import com.bxane.nuvx.ui.components.RemoteStorageSettingsDialogs
import com.bxane.nuvx.ui.components.ProviderLoginScreens
import androidx.media3.common.Player
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.TopPicks
import com.bxane.nuvx.data.model.TopPick
import com.bxane.nuvx.data.model.TopPickKind
import com.bxane.nuvx.data.model.TopPickMood
import com.bxane.nuvx.ui.screens.MoodGridPage
import com.bxane.nuvx.ui.player.NowPlayingScreen
import com.bxane.nuvx.ui.player.MixViewRequest
import com.bxane.nuvx.ui.player.MusicVideoEngine
import com.bxane.nuvx.ui.player.PlayerSheetMotion
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.onSizeChanged
import com.bxane.nuvx.ui.player.LocalPlayerDock
import com.bxane.nuvx.ui.player.PlayerDock
import com.bxane.nuvx.ui.screens.DetailScreen
import com.bxane.nuvx.ui.screens.sortedForDetail
import com.bxane.nuvx.ui.screens.ExploreScreen
import com.bxane.nuvx.ui.screens.LocalFolder
import com.bxane.nuvx.ui.screens.LocalFolderState
import com.bxane.nuvx.ui.screens.LocalFolderTab
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.toGroup
import com.bxane.nuvx.data.settings.LibraryViewType
import com.bxane.nuvx.data.settings.LocalMusicSort
import com.bxane.nuvx.ui.screens.HomeScreen
import com.bxane.nuvx.ui.screens.LibraryGridPage
import com.bxane.nuvx.ui.screens.LibraryScreen
import com.bxane.nuvx.ui.screens.MoodGenrePlaylistsScreen
import com.bxane.nuvx.ui.screens.SearchScreen
import com.bxane.nuvx.data.settings.SongSort
import com.bxane.nuvx.ui.onboarding.OnboardingScreen
import com.bxane.nuvx.ui.festive.DiwaliGate
import com.bxane.nuvx.ui.festive.DiwaliGreetingScreen
import com.bxane.nuvx.ui.replay.ReplayScreen
import com.bxane.nuvx.ui.replay.cards
import com.bxane.nuvx.ui.replay.ReplayShareSheet
import com.bxane.nuvx.ui.replay.ReplayStories
import com.bxane.nuvx.ui.replay.ReplayStoryPage
import com.bxane.nuvx.ui.replay.rememberReplayState
import com.bxane.nuvx.ui.theme.rememberArtworkPalette
import com.bxane.nuvx.data.canvas.AppleArtistArtRepository
import com.bxane.nuvx.data.canvas.keyColors
import com.bxane.nuvx.ui.theme.SystemBarIcons
import com.bxane.nuvx.ui.utils.guardSheetFromContentTouches
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import com.bxane.nuvx.data.radio.RadioBrowser
import com.bxane.nuvx.ui.screens.RadioScreen
import com.bxane.nuvx.ui.screens.RadioSymbol
import com.bxane.nuvx.ui.screens.toSong
import com.bxane.nuvx.ui.components.AppPrompts
import com.bxane.nuvx.recognition.RecognitionSheet
import com.bxane.nuvx.recognition.TopBarRecognizeButton
import com.bxane.nuvx.ui.components.TracedMark
import com.bxane.nuvx.data.model.MoodGenre
import com.composables.sfsymbols.SfSymbol


private const val TOP_FADE_START = 0.55f

private const val TOP_FADE_RAMP = 0.3f


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NuvxApp(
    darkTheme: Boolean,
    windowWidth: Dp,
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == TAB_EXPLORE) viewModel.onExploreShown()
    }
    var showNowPlaying by remember { mutableStateOf(false) }
    androidx.compose.runtime.SideEffect { VolumeHud.suppressed = showNowPlaying }
    val appForeground = com.bxane.nuvx.ui.rememberIsForeground()
    val artworkMotion = appForeground && !showNowPlaying
    LaunchedEffect(appForeground) {
        if (appForeground) viewModel.refreshTopPicksIfStale()
    }
    val playerDock = remember { PlayerDock() }
    val playerSheetScope = rememberCoroutineScope()
    val playerSheetMotion = remember {
        PlayerSheetMotion(
            scope = playerSheetScope,
            dock = playerDock,
            shown = { showNowPlaying },
            setShown = { showNowPlaying = it },
        )
    }
    playerSheetMotion.windowInfo = LocalWindowInfo.current
    val coveredByPlayer by remember {
        derivedStateOf { showNowPlaying && playerDock.attached && playerDock.openFraction() >= 1f }
    }
    val reducePlayerMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val activeDock = playerDock.takeIf { !reducePlayerMotion }
    playerSheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    val dismissPlayer: () -> Unit = {
        if (reducePlayerMotion) showNowPlaying = false else playerSheetMotion.close()
    }
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    var webSession by remember { mutableStateOf<WebSessionMode?>(null) }
    var replayLandingPage by rememberSaveable { mutableStateOf(ReplayStoryPage.INTRO) }
    var replayStory by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by rememberSaveable { mutableStateOf(false) }
    var replaySharePage by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showSpotifyCanvasAuth by remember { mutableStateOf(false) }

    var editingSource by remember { mutableStateOf<SourceConfig?>(null) }
    var choosingSourceQuality by remember { mutableStateOf<SourceConfig?>(null) }
    var explainingOutdatedAddon by remember { mutableStateOf<SourceConfig?>(null) }
    var confirmJioSaavn by remember { mutableStateOf(false) }
    var confirmBitPerfect by remember { mutableStateOf<BitPerfectMode?>(null) }
    var confirmFixedVolume by remember { mutableStateOf(false) }
    var blockedAddonSong by remember { mutableStateOf<com.bxane.nuvx.data.model.Song?>(null) }
    var addonLeftOutOfDownload by remember {
        mutableStateOf<Pair<String, SourceRegistry.DownloadExclusion>?>(null)
    }
    var editingPartyServer by remember { mutableStateOf(false) }
    var editingSignatureApi by remember { mutableStateOf(false) }
    var librarySortMenuOpen by remember { mutableStateOf(false) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    var showAccountSelector by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    var showWebDavEditor by remember { mutableStateOf(false) }
    var showSmbEditor by remember { mutableStateOf(false) }
    var showDiscordLogin by remember { mutableStateOf(false) }
    var showSpotifyLogin by remember { mutableStateOf(false) }
    var discordDialog by remember { mutableStateOf<DiscordDialog?>(null) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var showLyricsOffset by remember { mutableStateOf(false) }
    var menuFromPlayer by remember { mutableStateOf(false) }
    var menuFromHistory by remember { mutableStateOf(false) }
    var songMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    var songMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    val playerBackdrop = rememberSnapshotBackdrop()
    val livePlayerBackdrop = rememberLayerBackdrop()
    var playerLiveCanvas by remember { mutableStateOf(false) }
    val pageBackdrop = rememberSnapshotBackdrop()
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songMenuOrigin = LongPressOrigin.consume()
        songMenuAnchor = MenuAnchor.consume()
        songActions = song
    }
    var linksLoading by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var offlinePlaylistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingOfflinePlaylist by remember { mutableStateOf(false) }
    var showSpotifyImportDialog by remember { mutableStateOf(false) }
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    var browseMenuOrigin by remember { mutableStateOf<HeldItem?>(null) }
    var browseMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    var browseRenameInSheet by remember { mutableStateOf(false) }
    var reorderTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var reorderEntries by remember { mutableStateOf<UiState<List<Song>>>(UiState.Loading) }
    var reorderSaving by remember { mutableStateOf(false) }
    val openBrowseMenu: (BrowseTarget) -> Unit = { target ->
        browseMenuOrigin = LongPressOrigin.consume()
        browseMenuAnchor = MenuAnchor.consume()
        browseRenameInSheet = false
        browseActions = target
    }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val partyState by ListenTogether.state.collectAsStateWithLifecycle()
    val autoplayEnabled = autoplayEnabledFor(partyState, autoplay)
    val partyServerStatus by ListenTogether.serverConnectionState.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    var searchFocusRequested by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }
    val playRequestGeneration = remember { mutableIntStateOf(0) }
    val activeRadioSeed = remember { mutableStateOf<Pair<String, String>?>(null) }

    val onboardingComplete by AppSettings.onboardingComplete.collectAsStateWithLifecycle()
    val onboardingShown = remember { MutableTransitionState(!onboardingComplete) }
    onboardingShown.targetState = !onboardingComplete
    val onboardingVisible = onboardingShown.currentState || onboardingShown.targetState

    val diwaliFestive = remember(appForeground) {
        DiwaliGate.isIndianTimezone() && DiwaliGate.isDiwaliDay()
    }
    var showDiwali by rememberSaveable { mutableStateOf(false) }
    var diwaliAutoShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(diwaliFestive, onboardingComplete) {
        if (diwaliFestive && onboardingComplete && !diwaliAutoShown) {
            diwaliAutoShown = true
            if (!AppSettings.diwaliGreetingShown.value) showDiwali = true
        }
    }

    val navigator = viewModel.navigator
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val topEntry = navigator.visibleEntry(detailStack.size)
    val artistShelfRoute = topEntry?.route as? AppRoute.ArtistShelf
    val visibleEntry = topEntry?.takeIf { artistShelfRoute == null }
    val detailActiveShelf = artistShelfRoute?.shelf
    val route = visibleEntry?.route
    val topDetail = detailStack.lastOrNull()
    val detail = topDetail?.takeIf { visibleEntry == null }
    LaunchedEffect(detail?.browseId) {
        if (reorderTarget?.browseId != detail?.browseId) reorderTarget = null
    }

    SystemBarIcons(
        dark = !darkTheme && !showNowPlaying && route != AppRoute.Replay && replayStory == null && !onboardingVisible && !showDiwali,
    )

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideHomeVideos by AppSettings.hideHomeVideos.collectAsStateWithLifecycle()
    val hideTopPicks by AppSettings.hideTopPicks.collectAsStateWithLifecycle()
    val hideHomeMoods by AppSettings.hideHomeMoods.collectAsStateWithLifecycle()
    val visibleHomeState = remember(homeState, hideHomeVideos, hideRecents) {
        val loaded = homeState as? UiState.Success
        if (loaded != null && (hideHomeVideos || hideRecents)) {
            var shelves = loaded.data
            if (hideRecents) shelves = shelves.filterNot { it.title == YtMusicRepository.RECENT_TITLE }
            if (hideHomeVideos) shelves = shelves.withoutVideos()
            UiState.Success(shelves)
        } else {
            homeState
        }
    }
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()
    val homeRecentlyPlayedLoading by viewModel.homeRecentlyPlayedLoading.collectAsStateWithLifecycle()
    val topPicks by viewModel.topPicks.collectAsStateWithLifecycle()
    val topPicksLoading by viewModel.topPicksLoading.collectAsStateWithLifecycle()

    var updateDialogShown by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showRecognizer by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    val updateNotice = updateAvailable

    LaunchedEffect(updateNotice) {
        if (updateNotice != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog = true
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val exploreState by viewModel.explore.collectAsStateWithLifecycle()
    val selectedMoodGenre by viewModel.selectedMoodGenre.collectAsStateWithLifecycle()
    val moodGenreShelves by viewModel.moodGenreShelves.collectAsStateWithLifecycle()
    val libraryState by viewModel.library.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val searchSource by viewModel.searchSource.collectAsStateWithLifecycle()
    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()
    val addonResults by viewModel.addonResults.collectAsStateWithLifecycle()
    val addonCeiling = AppSettings.ceilingFor(
        wifi = AppSettings.audioQualityWifi.collectAsStateWithLifecycle().value,
        cellular = AppSettings.audioQualityCellular.collectAsStateWithLifecycle().value,
        metered = AppSettings.meteredConnection.collectAsStateWithLifecycle().value,
        override = AppSettings.streamCeilingOverride.collectAsStateWithLifecycle().value,
    )
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val catalogLoading by viewModel.catalogLoading.collectAsStateWithLifecycle()
    val incomingJamInvite by JamInviteLink.pending.collectAsStateWithLifecycle()
    var activeJamInviteCode by rememberSaveable { mutableStateOf<String?>(null) }
    var activeJamInviteServer by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingJamInvite) {
        val invite = incomingJamInvite ?: return@LaunchedEffect
        activeJamInviteCode = invite.code
        activeJamInviteServer = invite.serverUrl
        dismissPlayer()
        replayStory = null
        showReplayShare = false
        webSession = null
        navigator.reset(AppRoute.Settings, AppRoute.ListenTogether)
        JamInviteLink.handled()
    }
    LaunchedEffect(signedIn, activeJamInviteCode) {
        if (signedIn && activeJamInviteCode != null) {
            navigator.pushAll(AppRoute.Settings, AppRoute.ListenTogether)
        }
    }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val selectedChannelName by viewModel.selectedChannelName.collectAsStateWithLifecycle()
    val googleAccounts by viewModel.googleAccounts.collectAsStateWithLifecycle()
    val activeAccountId by viewModel.activeAccountId.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val historyState by viewModel.history.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val searchLoadingMore by viewModel.searchLoadingMore.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()
    val releaseLibrary by viewModel.releaseLibrary.collectAsStateWithLifecycle()
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    val isReleaseDetail = detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = topDetail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    val downloadedMusicSort by AppSettings.downloadedMusicSort.collectAsStateWithLifecycle()
    val localMusicSort by AppSettings.localMusicSort.collectAsStateWithLifecycle()
    val downloadedMusicViewType by AppSettings.downloadedMusicViewType.collectAsStateWithLifecycle()
    val localMusicViewType by AppSettings.localMusicViewType.collectAsStateWithLifecycle()
    val folderSortOf: (String) -> LocalMusicSort = { id ->
        if (id == "local:downloads") downloadedMusicSort else localMusicSort
    }
    val folderViewTypeOf: (String) -> LibraryViewType = { id ->
        if (id == "local:downloads") downloadedMusicViewType else localMusicViewType
    }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var detailSearching by rememberSaveable(topDetail?.browseId) { mutableStateOf(false) }
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val offlineLiked by OfflinePlaylists.liked.collectAsStateWithLifecycle()
    val offlinePlaylists by OfflinePlaylists.playlists.collectAsStateWithLifecycle()
    val offlinePlaylistGroups = remember(offlinePlaylists) { offlinePlaylists.map { it.toGroup() } }
    val likeStatusOf: (Song) -> LikeStatus = { song ->
        val uri = song.localUri
        when {
            uri != null -> if (uri in offlineLiked) LikeStatus.LIKE else LikeStatus.INDIFFERENT
            else -> likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT
        }
    }
    val toggleLikeOf: (Song) -> Unit = { song ->
        if (song.localUri != null) OfflinePlaylists.toggleLike(song) else viewModel.toggleLike(song.videoId)
    }
    val pinnedToOriginal by OriginalVersion.pinned.collectAsStateWithLifecycle()
    val qualityUpgradesInFlight by NerdStats.racingLossless.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()

    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val webdavUrl by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    val savedCollections by Downloads.collections.collectAsStateWithLifecycle()
    val downloadedReleases = remember(savedCollections, savedDownloads) {
        Downloads.savedReleases()
    }
    val localPlaylists by com.bxane.nuvx.data.spotify.LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val localPlaylistItems = localPlaylists.map { playlist ->
        ShelfItem(
            title = playlist.title,
            subtitle = stringResource(R.string.local_playlist_subtitle, playlist.songs.size),
            thumbnailUrl = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            videoId = null,
            browseId = playlist.browseId,
        )
    }
    val downloadIdFor: (String?) -> String? = { id ->
        id?.let { Downloads.recordIdOf(it) ?: it }?.takeIf { it in savedCollections }
    }
    LaunchedEffect(savedDownloads, savedCollections, topDetail?.browseId) {
        val openPage = topDetail ?: return@LaunchedEffect
        val open = openPage.browseId
        val folder = LocalGroups.folderOf(open) ?: open
        if (openPage.songs !is UiState.Loading &&
            (folder == "local:downloads" || folder == CACHE_FOLDER_BROWSE_ID || Downloads.recordIdOf(open) != null ||
                com.bxane.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(open) != null)
        ) {
            viewModel.reloadLocalDetail(open)
        }
    }
    LaunchedEffect(topDetail?.browseId, offlinePlaylists) {
        val openPage = topDetail ?: return@LaunchedEffect
        if (openPage.songs !is UiState.Loading && OfflinePlaylists.isOfflinePlaylist(openPage.browseId)) {
            viewModel.reloadLocalDetail(openPage.browseId)
        }
    }
    LaunchedEffect(localMusicFolderUri, filterNonMusicAudio) {
        val open = topDetail?.browseId
        if (open == "local:all" || LocalGroups.folderOf(open) == "local:all") {
            viewModel.reloadLocalDetail(open!!)
        }
    }
    LaunchedEffect(webdavUrl) {
        if (topDetail?.browseId == com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID)
        }
    }
    LaunchedEffect(smbHost) {
        if (topDetail?.browseId == com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID)
        }
    }
    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    DisposableEffect(controller) {
        MusicVideoEngine.attach(controller)
        onDispose { MusicVideoEngine.attach(null) }
    }
    val awaitingPartyStart by ListenTogether.awaitingStart.collectAsStateWithLifecycle()
    val playPauseBusy = player.isLoading || awaitingPartyStart
    val controlsLocked = partyState.controlsLocked
    var queueNotice by remember { mutableStateOf<QueueActionNotice?>(null) }
    var queueNoticeId by remember { mutableIntStateOf(0) }
    val showQueueNotice: (String) -> Unit = { message ->
        queueNoticeId += 1
        queueNotice = QueueActionNotice(queueNoticeId, message)
    }
    LaunchedEffect(queueNotice?.id) {
        val shown = queueNotice ?: return@LaunchedEffect
        delay(3_000)
        if (queueNotice?.id == shown.id) queueNotice = null
    }
    val hostOnlyMessage = stringResource(R.string.listen_together_host_only_notice)
    val showHostOnlyNotice: () -> Unit = { showQueueNotice(hostOnlyMessage) }

    val refusedByHost: () -> Boolean = {
        val locked = ListenTogether.state.value.controlsLocked
        if (locked) showHostOnlyNotice()
        locked
    }

    val togglePlayPause: () -> Unit = {
        controller?.let { c ->
            val party = ListenTogether.state.value
            if (party.controlsLocked && !party.playback.isPlaying && !c.isPlaying) {
                showHostOnlyNotice()
            } else if (c.isPlaying) {
                c.pause()
            } else {
                c.play()
            }
        }
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    val versionContext = LocalContext.current
    val missingVersions = remember { mutableStateListOf<String>() }
    val convertedFromVideoState = remember { mutableStateOf<Song?>(null) }
    var convertedFromVideo by convertedFromVideoState
    val convertedAudioIdState = remember { mutableStateOf<String?>(null) }
    var convertedAudioId by convertedAudioIdState
    val switchingAudioVersionState = remember { mutableStateOf(false) }
    var switchingAudioVersion by switchingAudioVersionState
    val keepVideoIdState = remember { mutableStateOf<String?>(null) }
    var keepVideoId by keepVideoIdState
    val convertedFromAudioState = remember { mutableStateOf<Song?>(null) }
    var convertedFromAudio by convertedFromAudioState
    val convertedVideoIdState = remember { mutableStateOf<String?>(null) }
    var convertedVideoId by convertedVideoIdState
    val optimisticVersionSongState = remember { mutableStateOf<Song?>(null) }
    var optimisticVersionSong by optimisticVersionSongState

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val lyricsSourceOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    LaunchedEffect(
        player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources,
        lyricsSourceOrder, prioritizeSyllableSync,
    ) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                player.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
            )
        }
    }

    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val moodGenreListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val libraryShowAllGridState = rememberLazyGridState()
    val moodsGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    val radioListState = rememberLazyListState()
    val podcastsListState = rememberLazyListState()
    val currentListState = when (selectedTab) {
        TAB_HOME -> homeListState
        TAB_EXPLORE -> if (selectedMoodGenre == null) exploreListState else moodGenreListState
        TAB_LIBRARY -> libraryListState
        TAB_RADIO -> radioListState
        TAB_PODCASTS -> podcastsListState
        else -> searchListState
    }

    val homePull = rememberPullToRefreshState()
    val explorePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currentFeed = when {
        route != null || detail != null -> null
        selectedTab == TAB_HOME -> MainViewModel.Feed.HOME
        selectedTab == TAB_EXPLORE -> MainViewModel.Feed.EXPLORE
        selectedTab == TAB_LIBRARY -> MainViewModel.Feed.LIBRARY
        else -> null
    }
    LaunchedEffect(currentFeed) {
        if (currentFeed == MainViewModel.Feed.HOME) viewModel.onHomeShown()
        if (currentFeed == MainViewModel.Feed.LIBRARY) viewModel.onLibraryShown()
    }

    val currentPull = when (currentFeed) {
        MainViewModel.Feed.HOME -> homePull
        MainViewModel.Feed.EXPLORE -> explorePull
        MainViewModel.Feed.LIBRARY -> libraryPull
        null -> null
    }
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    val replayEntryId = navigator.entries.lastOrNull { it.route == AppRoute.Replay }?.id
    val replayListState = remember(replayEntryId, replayLandingPage) { LazyListState() }
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    val detailListStates = remember { mutableMapOf<String, LazyListState>() }
    val detailListState = topDetail?.browseId?.let { browseId ->
        detailListStates.getOrPut(browseId) { LazyListState() }
    } ?: remember { LazyListState() }
    val localFolderStates = remember { mutableMapOf<String, LocalFolderState>() }
    LaunchedEffect(detailStack.map { it.browseId }) {
        val open = detailStack.mapTo(HashSet()) { it.browseId }
        detailListStates.keys.retainAll(open)
        localFolderStates.keys.retainAll(open)
    }
    val pageStates = rememberSaveableStateHolder()
    val pageKeysSeen = remember { mutableSetOf<String>() }
    val livePageKeys = navigator.entries.map { AppDestination.Route(it).key } +
        detailStack.map { AppDestination.Release(it.browseId).key }
    LaunchedEffect(livePageKeys) {
        val gone = pageKeysSeen.filter { it !in livePageKeys }
        gone.forEach(pageStates::removeState)
        pageKeysSeen.removeAll(gone.toSet())
    }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    val homeLabel = stringResource(R.string.home)
    val playLabel = stringResource(R.string.play)
    val exploreLabel = stringResource(R.string.explore)
    val libraryLabel = stringResource(R.string.library)
    val searchLabel = stringResource(R.string.search)
    val radioLabel = stringResource(R.string.radio)
    val podcastsLabel = stringResource(R.string.podcasts)
    val historyLabel = stringResource(R.string.history)
    val replayLabel = stringResource(R.string.replay)
    val queueLabel = stringResource(R.string.queue)
    val sharedLinkLabel = stringResource(R.string.shared_link)
    val openedFileLabel = stringResource(R.string.opened_file)
    val tabs = remember(homeLabel, exploreLabel, libraryLabel, radioLabel, searchLabel, podcastsLabel) {
        listOf(
            BottomTab(homeLabel, SfIcons.Home, SfSymbol.named("house.fill")),
            BottomTab(libraryLabel, SfIcons.TabLibrary, SfSymbol.named("square.stack.fill")),
            BottomTab(radioLabel, RadioSymbol.toImageVector(), RadioSymbol),
            BottomTab(exploreLabel, SfIcons.TabExplore, SfSymbol.named("square.grid.2x2.fill")),
            BottomTab(searchLabel, SfIcons.Search, SfSymbol.named("magnifyingglass")),
            BottomTab(podcastsLabel, PodcastsSymbol.toImageVector(), PodcastsSymbol),
        )
    }
    val hideRadioTab by AppSettings.hideRadioTab.collectAsStateWithLifecycle()
    val hidePodcastsTab by AppSettings.hidePodcastsTab.collectAsStateWithLifecycle()
    val youtubeSearch by AppSettings.youtubeSearch.collectAsStateWithLifecycle()
    LaunchedEffect(youtubeSearch) {
        if (!youtubeSearch && viewModel.filter.value == SearchFilter.YOUTUBE) viewModel.onFilterChange(SearchFilter.ALL)
    }
    val navTabIds = remember(hideRadioTab, hidePodcastsTab) {
        listOf(TAB_HOME, TAB_LIBRARY, TAB_RADIO, TAB_PODCASTS, TAB_EXPLORE, TAB_SEARCH)
            .filter { (it != TAB_RADIO || !hideRadioTab) && (it != TAB_PODCASTS || !hidePodcastsTab) }
    }
    val navTabs = remember(tabs, navTabIds) { navTabIds.map { tabs[it] } }

    val scope = rememberCoroutineScope()
    val playbackStarter = MainPlaybackStarter(
        context = context,
        scope = scope,
        controller = { controller },
        currentQueue = { player.queue },
        refusedByHost = refusedByHost,
        playRequestGeneration = playRequestGeneration,
        activeRadioSeed = activeRadioSeed,
    )

    fun uploadToWebDav(songs: List<Song>) {
        scope.launch {
            val summary = com.bxane.nuvx.data.webdav.WebDavUploads.upload(context, songs)
            if (summary.total > 0) {
                showQueueNotice(
                    context.getString(
                        R.string.webdav_upload_summary,
                        summary.uploaded,
                        summary.skipped,
                        summary.failed,
                    ),
                )
            }
            if (summary.uploaded > 0 &&
                topDetail?.browseId == com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID
            ) {
                viewModel.reloadLocalDetail(com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
    }

    val playbackVersionSwitcher = PlaybackVersionSwitcher(
        mediaController = controller,
        versionContext = versionContext,
        scope = scope,
        showQueueNotice = showQueueNotice,
        missingVersions = missingVersions,
        switchingAudioVersion = switchingAudioVersionState,
        keepVideoId = keepVideoIdState,
        convertedFromVideo = convertedFromVideoState,
        convertedAudioId = convertedAudioIdState,
        convertedFromAudio = convertedFromAudioState,
        convertedVideoId = convertedVideoIdState,
        optimisticVersionSong = optimisticVersionSongState,
        activeRadioSeed = activeRadioSeed,
    )

    fun playFrom(
        allSongs: List<Song>,
        allIndex: Int,
        source: QueueSource,
        boundedContext: Boolean = false,
        continuationToken: String? = null,
    ) = playbackStarter.playFrom(allSongs, allIndex, source, boundedContext, continuationToken)
    val play: (List<Song>, Int) -> Unit = { songs, index ->
        val first = songs.getOrNull(index)
        val source = QueueSource(
            title = first?.playbackSource ?: first?.albumName ?: queueLabel,
            type = first?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            id = first?.playbackSourceId,
        )
        playFrom(songs, index, source)
    }
    val playbackQueueActions = PlaybackQueueActions(
        scope = scope,
        context = context,
        mediaController = { controller },
        currentQueue = { player.queue },
        queueLabel = queueLabel,
        refusedByHost = refusedByHost,
        showQueueNotice = showQueueNotice,
        play = { songs, index -> play(songs, index) },
    )
    val browseOpener = remember(context, scope, viewModel) {
        MainBrowseOpener(context, scope, viewModel::openDetail)
    }
    LaunchedEffect(player.song?.videoId) {
        playbackVersionSwitcher.onCurrentTrackChanged(player.song?.videoId)
    }

    val playRadio: (Song, QueueSource) -> Unit = playbackStarter::playRadio

    val startRadio: (Song) -> Unit = playbackStarter::startRadio
    val openTopPick: (TopPick, String) -> Unit = { pick, label ->
        val browseId = pick.browseId
        if (pick.kind == TopPickKind.LATEST_RELEASE && browseId != null) {
            viewModel.openDetail(
                browseId = browseId,
                title = pick.title,
                subtitle = pick.artistName.orEmpty(),
                thumbnailUrl = pick.images.firstOrNull(),
            )
        } else {
            playbackStarter.playTopPick(pick, label)
        }
    }
    val playMood: (TopPickMood, String) -> Unit = { mood, title ->
        val hints = topPicks?.firstOrNull { it.kind == TopPickKind.MOOD }?.hints
        openTopPick(
            if (hints != null) TopPicks.moodStation(mood, hints) else TopPicks.moodStation(mood),
            title,
        )
    }
    val addToQueue: (Song) -> Unit = playbackQueueActions::addToQueue
    val playNext: (Song) -> Unit = playbackQueueActions::playNext
    val onSongSwipe: (Song) -> Unit = playbackQueueActions::onSongSwipe

    fun openByName(
        browseId: String?,
        name: String,
        subtitle: String?,
        type: BrowseType,
        artwork: String? = null,
    ) = browseOpener.openByName(browseId, name, subtitle, type, artwork)

    val addSongsToQueue: (List<Song>) -> Unit = { songs -> playbackQueueActions.queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> playbackQueueActions.queueSongs(songs, true) }

    val linkRequest by MusicLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(linkRequest, controller) {
        val request = linkRequest ?: return@LaunchedEffect
        val session = controller ?: return@LaunchedEffect
        MainMusicLinkHandler(
            context = context,
            viewModel = viewModel,
            playbackStarter = playbackStarter,
            dismissPlayer = dismissPlayer,
            navigator = navigator,
            searchTab = TAB_SEARCH,
            sharedLinkLabel = sharedLinkLabel,
            searchLabel = searchLabel,
            openedFileLabel = openedFileLabel,
            selectTab = { selectedTab = it },
            setShowNowPlaying = { showNowPlaying = it },
        ).handle(request, session)
    }

    val onBrowseLongPress: (ShelfItem) -> Unit = { item ->
        val id = item.browseId
        val type = id?.let { viewModel.browseTypeOf(it) }
        if (id != null && type != BrowseType.ARTIST) {
            openBrowseMenu(
                BrowseTarget(
                    browseId = id,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = type ?: BrowseType.OTHER,
                    downloadId = downloadIdFor(id),
                ),
            )
        }
    }

    val shelfSong: (ShelfItem) -> Song? = { item ->
        item.videoId?.let { videoId ->
            Song(
                videoId = videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
                isVideo = item.isVideo,
            )
        }
    }

    val onShelfLongPress: (ShelfItem) -> Unit = { item ->
        val song = shelfSong(item)
        if (song != null) openSongMenu(song) else onBrowseLongPress(item)
    }

    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                songs.map { song ->
                    song.copy(
                        albumName = if (target.type == BrowseType.ALBUM) {
                            song.albumName ?: target.title
                        } else {
                            song.albumName
                        },
                        playbackSource = target.title,
                        playbackSourceType = PlaybackSourceType.BROWSE,
                        playbackSourceId = target.browseId,
                    )
                },
            )
        }
        when {
            target.songs.isNotEmpty() -> stamp(target.songs)
            target.browseId == null ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
            else -> viewModel.collectSongs(target.browseId, target.thumbnailUrl) { result ->
                result.fold(
                    onSuccess = stamp,
                    onFailure = {
                        val message = it.message ?: context.getString(R.string.couldnt_load_tracks)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    var downloadPending by remember { mutableStateOf<List<Pair<List<Song>, DownloadTarget?>>>(emptyList()) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val batches = downloadPending
        downloadPending = emptyList()
        when {
            batches.isEmpty() -> Unit
            granted -> batches.forEach { (songs, from) ->
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
            else -> Toast
                .makeText(context, context.getString(R.string.storage_required_save), Toast.LENGTH_SHORT)
                .show()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
            viewModel.reloadLocalDetail("local:downloads")
            viewModel.loadLibrarySongs()
        } else {
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val onSearchSourceChange: (SearchSource) -> Unit = { source ->
        if (source == SearchSource.LIBRARY && !LocalMediaRepository.hasStoragePermission(context)) {
            mediaPermissionLauncher.launch(mediaPermission)
        }
        viewModel.setSearchSource(source)
    }
    val onLibraryItemClick: (ShelfItem) -> Unit = { item ->
        item.browseId?.let { id ->
            if (id == SPOTIFY_BROWSE_ID) {
                navigator.push(AppRoute.Spotify)
                return@let
            }
            if ((id == "local:all" || id == "local:downloads") && !LocalMediaRepository.hasStoragePermission(context)) {
                mediaPermissionLauncher.launch(mediaPermission)
            }
            viewModel.openDetail(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
    }
    val startDownloads: (List<Pair<List<Song>, DownloadTarget?>>) -> Unit = { batches ->
        val saved = Downloads.saved.value
        val todo = batches.map { (requested, from) ->
            requested
                .filter { it.videoId !in saved }
                .map { song ->
                    val cover = from?.thumbnailUrl
                    if (song.thumbnailUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                        song.copy(thumbnailUrl = cover)
                    } else {
                        song
                    }
                } to from
        }
        val songs = todo.flatMap { it.first }.distinctBy { it.videoId }
        val blocked = songs.isNotEmpty() && !AppSettings.downloadsAllowedNow &&
            songs.none { Downloads.servableFromCache(it.videoId) }
        if (songs.isNotEmpty() && !blocked) {
            val needsStorage = AppSettings.exportDownloads.value && DownloadStore.needsLegacyPermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                val ask = downloadPending.isEmpty()
                downloadPending = downloadPending + todo.filter { it.first.isNotEmpty() }
                if (ask) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                todo.forEach { (batch, from) ->
                    batch.forEach { Downloads.enqueue(context, it, from?.title) }
                    if (from != null) Downloads.markRequested(from.id, batch.map { it.videoId })
                }
            }
            if (!songs.all { Downloads.servableFromCache(it.videoId) }) {
                SourceRegistry.addonsLeftOutOfDownloads()
                    .firstOrNull { (config, why) -> explainedDownloadExclusions.add("${config.id}:$why") }
                    ?.let { (config, why) -> addonLeftOutOfDownload = config.displayName to why }
            }
        }
        if (!blocked) batches.forEach { (requested, from) ->
            if (from == null) return@forEach
            Downloads.rememberCollection(from, requested)
            if (!from.thumbnailUrl.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    MediaTagger.cacheArtwork(context.applicationContext, from.thumbnailUrl)
                        ?.let { Downloads.rememberCollectionArtwork(from.id, it) }
                }
            }
        }
        when {
            blocked -> Toast.makeText(
                context,
                context.getString(R.string.wifi_only_download_refusal),
                Toast.LENGTH_LONG,
            ).show()
            batches.sumOf { it.first.size } > 1 -> {
                val message = if (songs.isEmpty()) {
                    context.getString(R.string.already_downloaded)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.downloading_song_count,
                        songs.size,
                        songs.size,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val startDownload: (List<Song>, DownloadTarget?) -> Unit = { requested, from ->
        startDownloads(listOf(requested to from))
    }
    val downloadCatalog: (DetailPage) -> Unit = { page ->
        viewModel.collectCatalog(page) { releases ->
            if (releases.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.couldnt_load_tracks), Toast.LENGTH_SHORT).show()
            } else {
                val all = releases.flatMap { it.second }
                Downloads.markRequested(page.browseId, all.map { it.videoId })
                val albums = releases.map { (release, songs) ->
                    songs to DownloadTarget(
                        id = release.browseId!!,
                        title = release.title,
                        subtitle = release.subtitle,
                        thumbnailUrl = release.thumbnailUrl,
                    )
                }
                val artist = DownloadTarget(
                    id = page.browseId,
                    title = page.title,
                    subtitle = page.subtitle,
                    thumbnailUrl = page.thumbnailUrl,
                    artist = true,
                )
                startDownloads(albums + (all to artist))
            }
        }
    }
    val downloadFromCache: (List<Song>) -> Unit = { songs ->
        val cleaned = songs.map { it.copy(downloadFormat = null, localDateAddedSeconds = null) }
        Downloads.preferCachedCopy(cleaned.map { it.videoId })
        startDownload(cleaned, null)
    }
    val removedFromCacheMessage = stringResource(R.string.removed_from_cache)
    val removeFromCache: (List<Song>) -> Unit = { songs ->
        scope.launch {
            val removed = songs.sumOf { AudioCache.remove(it.videoId) }
            if (removed > 0) showQueueNotice(removedFromCacheMessage)
        }
    }
    val downloadSong: (Song) -> Unit = { song ->
        val withDuration = if (song.durationMillis() <= 0L &&
            player.song?.videoId == song.videoId && player.durationMs > 0L
        ) {
            song.copy(durationText = formatDurationText(player.durationMs))
        } else {
            song
        }
        startDownload(listOf(withDuration), null)
    }

    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    val appleArtVersion by AppleArtistArtRepository.updates.collectAsStateWithLifecycle()
    val detailApple = remember(topDetail?.browseId, topDetail?.type, appleArtVersion) {
        topDetail?.takeIf { it.type == BrowseType.ARTIST }
            ?.let { AppleArtistArtRepository.cached(it.browseId) }
    }
    val detailPalette = rememberArtworkPalette(
        imageUrl = detailApple?.heroUrl ?: topDetail?.thumbnailUrl,
        keyColors = detailApple?.keyColors(),
    )

    val replayOpen = route == AppRoute.Replay || replayStory != null || showReplayShare ||
        (selectedTab == TAB_LIBRARY && detail == null && route == null)
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) {
        replay.summary?.takeUnless { it.isEmpty }?.cards(context).orEmpty()
    }

    val playerShowing = showNowPlaying
    var links by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(player.song?.videoId, playerShowing) {
        links = null
        linksLoading = false
        if (!playerShowing) return@LaunchedEffect
        val current = player.song ?: return@LaunchedEffect
        if (current.albumId != null && current.artistId != null) return@LaunchedEffect
        linksLoading = true
        links = YtMusicRepository.trackLinks(current.videoId).getOrNull()
        linksLoading = false
    }
    val playerSong = player.song?.let { current ->
        val extra = links?.takeIf { it.videoId == current.videoId } ?: return@let current
        current.copy(
            artistId = current.artistId ?: extra.artistId,
            albumId = current.albumId ?: extra.albumId,
            albumName = current.albumName ?: extra.albumName,
        )
    }
    LaunchedEffect(playerSong) {
        if (playerSong != null && songActions?.videoId == playerSong.videoId) {
            songActions = playerSong
        }
    }

    val playbackSourceNavigator = MainPlaybackSourceNavigator(
        context = context,
        viewModel = viewModel,
        navigator = navigator,
        queueLabel = queueLabel,
        dismissPlayer = dismissPlayer,
        selectTab = { selectedTab = it },
        setReplayLandingPage = { replayLandingPage = it },
        homeTab = TAB_HOME,
        searchTab = TAB_SEARCH,
        exploreTab = TAB_EXPLORE,
    )

    val nowPlaying: @Composable (Song) -> Unit = { song ->
        val effectiveSong = optimisticVersionSong?.takeIf {
            it.videoId == convertedAudioId || it.videoId == convertedVideoId || it.videoId == keepVideoId ||
            it.videoId == YtMusicRepository.cachedAudioVersion(song.videoId)?.videoId ||
            it.videoId == YtMusicRepository.cachedVideoVersion(song.videoId)?.videoId
        } ?: song
        val displayedSong = activeRadioSeed.value
            ?.takeIf { (videoId, _) -> effectiveSong.radioName == null && videoId == effectiveSong.videoId }
            ?.let { (videoId, name) ->
                effectiveSong.copy(
                    radioName = name,
                    playbackSource = name,
                    playbackSourceType = PlaybackSourceType.SHARED_LINK,
                    playbackSourceId = videoId,
                )
            }
            ?: effectiveSong
        val playedBy = partyState
            .takeIf {
                it.inParty && it.playback.track?.videoId == displayedSong.videoId
            }
            ?.playback
            ?.let { playback ->
                playback.startedByName?.takeIf(String::isNotBlank)
                    ?: partyState.members.firstOrNull {
                        it.memberId == playback.startedBy
                    }?.displayName?.takeIf(String::isNotBlank)
            }
        NowPlayingScreen(
            song = displayedSong,
            playedBy = playedBy,
            accountName = account?.name,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            menuOpen = { songActions != null && menuFromPlayer && songMenuOrigin == null },
            onLiveCanvasChanged = { playerLiveCanvas = it },
            isPlaying = player.isPlaying,
            isLoading = playPauseBusy,
            position = player.position,
            durationMs = player.durationMs,
            audioVersionSwitching = switchingAudioVersion,
            qualityUpgraded = player.isQualityUpgraded,
            onPlayPause = {
                togglePlayPause()
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onBlockedControl = showHostOnlyNotice,
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplayEnabled,
            signedIn = signedIn,
            likeStatus = likeStatusOf(song),
            onToggleLike = { toggleLikeOf(song) },
            onToggleShuffle = { controller?.toggleShuffle() },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    AppSettings.setRepeatMode(next)
                    it.repeatMode = next
                }
            },
            onToggleAutoplay = {
                controller?.toggleAutoplay()
            },
            onJumpTo = { index ->
                controller?.let { c ->
                    c.seekTo(index, 0L)
                    c.play()
                }
            },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            onQueueDragActiveChange = { active -> controller?.setQueueDragActive(active) },
            onOpenMenu = {
                menuFromPlayer = true
                songMenuOrigin = null
                songMenuAnchor = MenuAnchor.consume()
                songActions = song
                if (!AppSettings.reduceDynamicBlur.value && !playerLiveCanvas) playerBackdrop.capture()
            },
            onOpenAlbum = { id ->
                dismissPlayer()
                viewModel.openDetail(
                    id,
                    song.albumName ?: song.title,
                    song.artist,
                    song.thumbnailUrl,
                    BrowseType.ALBUM,
                )
            },
            onOpenArtist = { id, name ->
                if (id != null) {
                    dismissPlayer()
                    viewModel.openDetail(
                        id,
                        name,
                        context.getString(R.string.artist),
                        null,
                        BrowseType.ARTIST,
                    )
                } else {
                    scope.launch {
                        val found = YtMusicRepository.findArtistPageId(name).getOrNull()
                        if (found != null) {
                            dismissPlayer()
                            viewModel.openDetail(
                                found,
                                name,
                                context.getString(R.string.artist),
                                null,
                                BrowseType.ARTIST,
                            )
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.couldnt_find, name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onOpenPlaybackSource = { playbackSourceNavigator.open(displayedSong) },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = viewModel::selectLyricsProvider,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = showLyricsOffset,
            onDismissLyricsOffset = { showLyricsOffset = false },
            onListenTogether = {
                dismissPlayer()
                navigator.pushAll(AppRoute.Settings, AppRoute.ListenTogether)
            },
            onClearQueue = {
                controller?.let { c ->
                    QueueCoordinator.clearUserQueue(c)
                }
            },
        )
    }

    val canGoBack = navigator.entries.isNotEmpty() || detailStack.isNotEmpty() ||
        selectedMoodGenre != null || selectedTab != TAB_HOME
    val goBack: () -> Unit = {
        if (!navigator.pop() && !viewModel.closeMoodGenre()) selectedTab = TAB_HOME
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                if (!coveredByPlayer) drawContent()
            },
    ) {
        BackHandler(enabled = canGoBack, onBack = goBack)
        val replayDrawable = replay.summary?.isEmpty == false
        BackHandler(enabled = replayDrawable && replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(enabled = replayDrawable && showReplayShare) { showReplayShare = false }
        BackHandler(enabled = showUpdateDialog) { showUpdateDialog = false }
        BackHandler(enabled = showListenBrainzLogin) { showListenBrainzLogin = false }
        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }
        BackHandler(enabled = discordDialog != null) { discordDialog = null }
        BackHandler(enabled = editingSource != null) { editingSource = null }
        BackHandler(enabled = choosingSourceQuality != null) { choosingSourceQuality = null }
        BackHandler(enabled = explainingOutdatedAddon != null) { explainingOutdatedAddon = null }
        BackHandler(enabled = editingPartyServer) { editingPartyServer = false }
        BackHandler(enabled = editingSignatureApi) { editingSignatureApi = false }

        Row(
            Modifier
                .fillMaxSize()
                .snapshotBackdropSource(pageBackdrop, floor = MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = when {
                        visibleEntry != null -> AppDestination.Route(visibleEntry)
                        detail != null -> AppDestination.Release(detail.browseId)
                        else -> AppDestination.Tab(selectedTab)
                    },
                    contentKey = { it.key },
                    transitionSpec = {
                        val initial = initialState
                        val target = targetState
                        val tabSwap = initial is AppDestination.Tab && target is AppDestination.Tab
                        val libraryTab = AppDestination.Tab(TAB_LIBRARY)
                        fun AppDestination.isShowAll() =
                            (this as? AppDestination.Route)?.entry?.route is AppRoute.LibraryShowAll
                        val libraryShowAllSwap = (initial.isShowAll() && target == libraryTab) ||
                            (target.isShowAll() && initial == libraryTab)
                        if (tabSwap || libraryShowAllSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (glassActive) {
                                Modifier
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { destination ->
                    val key = destination.key
                    val pageRoute = (destination as? AppDestination.Route)?.entry?.route
                    val live = (destination as? AppDestination.Release)?.let { release ->
                        detailStack.lastOrNull()?.takeIf { it.browseId == release.browseId }
                    }
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    val pageDetailListState = remember(key) {
                        detailListStates.getOrPut(key) { LazyListState() }
                    }
                    val pageContent: @Composable () -> Unit = {
                        if (pageRoute == AppRoute.History) {
                            HistoryScreen(
                                state = historyState,
                                listState = historyListState,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onSongClick = { songs, index ->
                                    playFrom(songs, index, QueueSource(historyLabel, PlaybackSourceType.HISTORY))
                                },
                                onSongLongPress = { song ->
                                    menuFromHistory = true
                                    openSongMenu(song)
                                },
                                onSongSwipe = onSongSwipe,
                                onRetry = viewModel::loadHistory,
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute is AppRoute.LibraryShowAll) {
                            pageRoute.shelf.let { shelf ->
                                LibraryGridPage(
                                    shelf = shelf,
                                    gridState = libraryShowAllGridState,
                                    onItemClick = onLibraryItemClick,
                                    onItemLongPress = onBrowseLongPress,
                                    onNewPlaylist = if (shelf.title == YtMusicRepository.PLAYLISTS_SHELF) {
                                        { creatingPlaylist = true }
                                    } else {
                                        null
                                    },
                                    contentPadding = listPadding,
                                )
                            }
                        } else if (pageRoute == AppRoute.Moods) {
                            MoodGridPage(
                                gridState = moodsGridState,
                                onClick = playMood,
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Replay) {
                            ReplayScreen(
                                state = replay,
                                holder = account?.name.orEmpty(),
                                onPeriodChange = setReplayPeriod,
                                onOpenStory = { replayStory = it },
                                onPlaySong = { song ->
                                    playRadio(song, QueueSource(replayLabel, PlaybackSourceType.REPLAY))
                                },
                                onOpenArtist = { id, name ->
                                    openByName(id, name, null, BrowseType.ARTIST)
                                },
                                onOpenAlbum = { id, title, artist, art ->
                                    openByName(id, title, artist, BrowseType.ALBUM, art)
                                },
                                onShare = {
                                    replaySharePage = null
                                    showReplayShare = true
                                },
                                contentPadding = listPadding,
                                listState = replayListState,
                                landingPage = replayLandingPage,
                            )
                        } else if (pageRoute == AppRoute.Spotify) {
                            SpotifyLibraryScreen(
                                onOpenPlaylist = { playlist ->
                                    viewModel.openDetail(
                                        browseId = SPOTIFY_PAGE_PREFIX + playlist.id,
                                        title = playlist.name,
                                        subtitle = playlist.owner ?: context.getString(R.string.spotify),
                                        thumbnailUrl = playlist.imageUrl,
                                        type = BrowseType.PLAYLIST,
                                    )
                                },
                                onOpenLogin = { showSpotifyLogin = true },
                                onOpenTokenSetup = { showSpotifyCanvasAuth = true },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Discord) {
                            DiscordScreen(
                                song = player.song,
                                positionMs = player.position.positionMs,
                                durationMs = player.durationMs,
                                onOpenLogin = { showDiscordLogin = true },
                                onOpenDialog = { discordDialog = it },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.AccountScrobbling) {
                            AccountAndScrobblingScreen(
                                signedIn = signedIn,
                                account = account,
                                channelName = selectedChannelName,
                                onSignIn = {
                                    navigator.clear()
                                    webSession = WebSessionMode.SIGN_IN
                                },
                                onSwitchChannel = {
                                    viewModel.loadChannels()
                                    showAccountSelector = true
                                },
                                onSignOut = { viewModel.signOut() },
                                onOpenListenBrainzLogin = { showListenBrainzLogin = true },
                                onOpenLastfmLogin = { showLastfmLogin = true },
                                onOpenDiscord = { navigator.push(AppRoute.Discord) },
                                onOpenSpotify = { navigator.push(AppRoute.Spotify) },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Sources) {
                            SourcesScreen(
                                contentPadding = listPadding,
                                onEditSource = { editingSource = it },
                                onChooseSourceQuality = { choosingSourceQuality = it },
                                onExplainOutdatedAddon = { explainingOutdatedAddon = it },
                                onEditWebDav = { showWebDavEditor = true },
                                onEditSmb = { showSmbEditor = true },
                                onConfirmJioSaavn = { confirmJioSaavn = true },
                            )
                        } else if (pageRoute == AppRoute.Downloads) {
                            DownloadManagerPage(
                                onCleared = { navigator.pop() },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.WearOs) {
                            WearOsScreen(contentPadding = listPadding)
                        } else if (pageRoute == AppRoute.ListenTogether) {
                            ListenTogetherScreen(
                                signedIn = signedIn,
                                inviteCode = activeJamInviteCode,
                                inviteServer = activeJamInviteServer,
                                onInviteHandled = {
                                    activeJamInviteCode = null
                                    activeJamInviteServer = null
                                },
                                onSignIn = {
                                    navigator.clear()
                                    webSession = WebSessionMode.SIGN_IN
                                },
                                contentPadding = listPadding,
                                onEditServer = { editingPartyServer = true },
                            )
                        } else if (pageRoute == AppRoute.Equalizer) {
                            EqualizerScreen(
                                contentPadding = listPadding,
                                onOpenPresets = { navigator.push(AppRoute.EqualizerPresets) },
                            )
                        } else if (pageRoute == AppRoute.EqualizerPresets) {
                            EqualizerPresetPage(
                                onChosen = { navigator.pop() },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute is AppRoute.SettingsChoice) {
                            SettingsChoicePage(
                                choice = pageRoute.choice,
                                onChosen = { navigator.pop() },
                                onConfirmBitPerfect = { confirmBitPerfect = it },
                                contentPadding = listPadding,
                            )
                        } else if (pageRoute == AppRoute.Settings || pageRoute is AppRoute.SettingsSection) {
                            SettingsScreen(
                                section = (pageRoute as? AppRoute.SettingsSection)?.category,
                                onOpenSection = { navigator.push(AppRoute.SettingsSection(it)) },
                                onOpenChoice = { navigator.push(AppRoute.SettingsChoice(it)) },
                                windowWidth = windowWidth,
                                signedIn = signedIn,
                                account = account,
                                onSignIn = {
                                    navigator.clear()
                                    webSession = WebSessionMode.SIGN_IN
                                },
                                onSignOut = { viewModel.signOut() },
                                onAccountScrobbling = { navigator.push(AppRoute.AccountScrobbling) },
                                onEqualizer = { navigator.push(AppRoute.Equalizer) },
                                onOpenReplay = {
                                    replayLandingPage = ReplayStoryPage.INTRO
                                    navigator.push(AppRoute.Replay)
                                },
                                onLyricsSources = { showLyricsSources = true },
                                onTranslationLanguage = { showTranslationLanguage = true },
                                onSources = { navigator.push(AppRoute.Sources) },
                                onListenTogether = { navigator.push(AppRoute.ListenTogether) },
                                onWearOs = { navigator.push(AppRoute.WearOs) },
                                onSpotifyCanvasAuth = { showSpotifyCanvasAuth = true },
                                onAppLanguage = { showAppLanguage = true },
                                onEditSignatureApi = { editingSignatureApi = true },
                                onConfirmBitPerfect = { confirmBitPerfect = it },
                                onConfirmFixedVolume = { confirmFixedVolume = true },
                                contentPadding = listPadding,
                            )
                        } else if (page != null) {
                            val folder = if (page.browseId.isDeviceFolder()) {
                                val isDownloads = page.browseId == "local:downloads"
                                val isCache = page.browseId == CACHE_FOLDER_BROWSE_ID
                                val isDevice = page.browseId == "local:all"
                                val folderSongs = (page.songs as? UiState.Success)?.data.orEmpty()
                                val downloadCollections = remember(folderSongs, savedCollections, isDownloads) {
                                    if (isDownloads) Downloads.collectionsAmong(folderSongs) else emptyList()
                                }
                                LocalFolder(
                                    state = localFolderStates.getOrPut(page.browseId) { LocalFolderState() },
                                    sort = folderSortOf(page.browseId),
                                    viewType = folderViewTypeOf(page.browseId),
                                    collections = downloadCollections,
                                    selectable = isDownloads || isCache,
                                    onOpenGroup = viewModel::openLocalGroup,
                                    onGroupLongPress = { group ->
                                        openBrowseMenu(
                                            BrowseTarget(
                                                browseId = group.browseId,
                                                title = group.title,
                                                subtitle = group.subtitle,
                                                thumbnailUrl = group.thumbnailUrl,
                                                type = group.type,
                                                songs = group.songs,
                                                downloadId = group.downloadId,
                                            ),
                                        )
                                    },
                                    onDelete = if (isDownloads) {
                                        { selected ->
                                            scope.launch {
                                                var failed = false
                                                selected.forEach { song ->
                                                    if (!Downloads.delete(context, song.videoId)) failed = true
                                                }
                                                if (failed) Toast.makeText(context, R.string.delete_download_failed, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onUpload = if (isDownloads &&
                                        com.bxane.nuvx.data.webdav.WebDavConfig.isConfigured(webdavUrl)
                                    ) {
                                        { selected -> uploadToWebDav(selected) }
                                    } else {
                                        null
                                    },
                                    onDownload = if (isCache) downloadFromCache else null,
                                    onRemoveFromCache = if (isCache) removeFromCache else null,
                                    tabs = if (isDevice) LocalFolderTab.DEVICE else LocalFolderTab.BASIC,
                                    playlists = if (isDevice) offlinePlaylistGroups else emptyList(),
                                    onCreatePlaylist = if (isDevice) ({ creatingOfflinePlaylist = true }) else null,
                                )
                            } else {
                                null
                            }
                            val withAlbum: (Song) -> Song = { song ->
                                if (page.type == BrowseType.ALBUM) {
                                    song.copy(albumName = song.albumName ?: page.title)
                                } else {
                                    song
                                }
                            }
                            fun playDetailSongs(songs: List<Song>, index: Int) {
                                if (ListenTogether.state.value.controlsLocked) {
                                    refusedByHost()
                                    return
                                }
                                val source = QueueSource(
                                    page.title,
                                    PlaybackSourceType.BROWSE,
                                    page.browseId,
                                )
                                val selected = songs.getOrNull(index)
                                val pageSongKeys =
                                    (page.songs as? UiState.Success)?.data.orEmpty().mapTo(HashSet()) { it.contextEntryKey() }
                                val useBounded =
                                    (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
                                        selected?.contextEntryKey()?.let(pageSongKeys::contains) == true &&
                                        !CastController.state.value.casting
                                val latestPage = if (useBounded) {
                                    viewModel.takePlaybackContext(page.browseId)
                                } else {
                                    null
                                }
                                val latestSongs = latestPage
                                    ?.let { (it.songs as? UiState.Success)?.data.orEmpty() }
                                    ?.map(withAlbum)
                                    ?.takeIf { it.isNotEmpty() }
                                val queue = latestSongs ?: songs.map(withAlbum)
                                if (queue.isEmpty()) return
                                val queueIndex = selected?.let { tapped ->
                                    queue.indexOfFirst { it.contextEntryKey() == tapped.contextEntryKey() }
                                }?.takeIf { it >= 0 } ?: index
                                playFrom(
                                    queue,
                                    queueIndex.coerceIn(queue.indices),
                                    source,
                                    boundedContext = useBounded,
                                    continuationToken = latestPage?.continuation ?: page.continuation,
                                )
                            }
                            val reordering = reorderTarget?.takeIf { it.browseId == page.browseId }
                            DetailScreen(
                                page = page,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                listState = pageDetailListState,
                                activeShelf = detailActiveShelf,
                                onActiveShelfChange = { shelf ->
                                    if (shelf != null) navigator.push(AppRoute.ArtistShelf(page.browseId, shelf))
                                    else navigator.pop()
                                },
                                onSongClick = ::playDetailSongs,
                                onSongLongPress = { openSongMenu(withAlbum(it)) },
                                onSongSwipe = { onSongSwipe(withAlbum(it)) },
                                onShuffle = { songs ->
                                    if (songs.isNotEmpty()) {
                                        QueueShuffle.enableForNextQueue()
                                        playDetailSongs(songs, songs.indices.random())
                                    }
                                },
                                onSectionItemClick = { item ->
                                    val video = shelfSong(item)?.takeIf { item.browseId == null }
                                    if (video != null) {
                                        playRadio(video, QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId))
                                    } else {
                                        item.browseId?.let { id ->
                                            viewModel.openDetail(
                                                browseId = id,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                thumbnailUrl = item.thumbnailUrl,
                                                type = BrowseType.ALBUM,
                                            )
                                        }
                                    }
                                },
                                onSectionItemLongPress = onShelfLongPress,
                                searching = detailSearching && page.browseId == detail?.browseId,
                                onSearchingChange = { detailSearching = it },
                                onArtistClick = { id, name ->
                                    viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
                                },
                                onAddSuggested = { song -> viewModel.addSuggestedSong(page.browseId, song) },
                                onToggleLibrary = if (signedIn) {
                                    { viewModel.toggleLibrary(page.browseId) }
                                } else {
                                    null
                                },
                                onToggleSubscription = if (signedIn) {
                                    { viewModel.toggleSubscription(page.browseId) }
                                } else {
                                    null
                                },
                                onDownloadCatalog = { downloadCatalog(page) },
                                catalogLoading = page.browseId in catalogLoading,
                                releaseLibrary = releaseLibrary,
                                onLoadReleaseLibrary = viewModel::loadReleaseLibrary,
                                onToggleReleaseLibrary = if (signedIn) {
                                    viewModel::toggleReleaseLibrary
                                } else {
                                    null
                                },
                                songSort = songSort,
                                folder = folder,
                                contentPadding = listPadding,
                                reorder = if (reordering != null) {
                                    { palette ->
                                        ReorderPlaylistPage(
                                            playlist = reordering,
                                            entries = reorderEntries,
                                            saving = reorderSaving,
                                            palette = palette,
                                            contentPadding = PaddingValues(
                                                bottom = listPadding.calculateBottomPadding(),
                                            ),
                                            onClose = { reorderTarget = null },
                                            onSave = { reordered ->
                                                val original = (reorderEntries as? UiState.Success)?.data.orEmpty()
                                                reorderSaving = true
                                                viewModel.reorderPlaylist(reordering, original, reordered) { saved ->
                                                    reorderSaving = false
                                                    showQueueNotice(
                                                        context.getString(
                                                            if (saved) R.string.playlist_reordered else R.string.reorder_failed,
                                                        ),
                                                    )
                                                    if (saved && reorderTarget == reordering) reorderTarget = null
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        } else when ((destination as? AppDestination.Tab)?.index ?: selectedTab) {
                            TAB_HOME -> HomeScreen(
                                state = visibleHomeState,
                                listState = homeListState,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                title = stringResource(R.string.listen_now),
                                festiveLogo = diwaliFestive,
                                showLogo = false,
                                onFestiveLogoClick = { showDiwali = true },
                                signedIn = signedIn,
                                onSignIn = { webSession = WebSessionMode.SIGN_IN },
                                onItemClick = { item, shelfTitle ->
                                    val song = shelfSong(item)
                                    val browseId = item.browseId
                                    when {
                                        song != null -> playRadio(
                                            song,
                                            QueueSource(shelfTitle, PlaybackSourceType.HOME),
                                        )
                                        browseId != null -> viewModel.openDetail(
                                            browseId = browseId,
                                            title = item.title,
                                            subtitle = item.subtitle,
                                            thumbnailUrl = item.thumbnailUrl,
                                        )
                                    }
                                },
                                onItemLongPress = onShelfLongPress,
                                onRetry = viewModel::loadHome,
                                refreshing = MainViewModel.Feed.HOME in refreshing,
                                onRefresh = { viewModel.refresh(MainViewModel.Feed.HOME) },
                                pullState = homePull,
                                contentPadding = listPadding,
                                onLoadMore = viewModel::loadMoreHome,
                                loadingMore = homeLoadingMore,
                                recentlyPlayedLoading = homeRecentlyPlayedLoading && !hideRecents,
                                topPicks = topPicks,
                                topPicksLoading = topPicksLoading,
                                listenerName = account?.name,
                                onTopPickClick = openTopPick.takeUnless { hideTopPicks },
                                onMoodClick = playMood.takeUnless { hideHomeMoods },
                                onMoodsShowAll = { navigator.push(AppRoute.Moods) },
                                artworkMotion = artworkMotion && !showDiwali,
                                onTopPickLongPress = { pick ->
                                    pick.browseId?.let { browseId ->
                                        onShelfLongPress(
                                            ShelfItem(
                                                title = pick.title,
                                                subtitle = pick.artistName.orEmpty(),
                                                thumbnailUrl = pick.images.firstOrNull(),
                                                videoId = null,
                                                browseId = browseId,
                                            ),
                                        )
                                    }
                                },
                            )
                            TAB_EXPLORE -> selectedMoodGenre?.let { category ->
                                MoodGenrePlaylistsScreen(
                                    title = category.title,
                                    state = moodGenreShelves,
                                    listState = moodGenreListState,
                                    onItemClick = { item ->
                                        item.videoId?.let { videoId ->
                                            playRadio(
                                                Song(
                                                    videoId = videoId,
                                                    title = item.title,
                                                    artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                                                    thumbnailUrl = item.thumbnailUrl,
                                                    isVideo = item.isVideo,
                                                ),
                                                QueueSource(
                                                    category.title,
                                                    PlaybackSourceType.EXPLORE,
                                                    category.browseId,
                                                ),
                                            )
                                        } ?: item.browseId?.let { browseId ->
                                            viewModel.openDetail(
                                                browseId = browseId,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                thumbnailUrl = item.thumbnailUrl,
                                            )
                                        }
                                    },
                                    onRetry = { viewModel.openMoodGenre(category) },
                                    contentPadding = listPadding,
                                )
                            } ?: ExploreScreen(
                                state = exploreState,
                                listState = exploreListState,
                                onCategoryClick = viewModel::openMoodGenre,
                                onRetry = viewModel::loadExplore,
                                refreshing = MainViewModel.Feed.EXPLORE in refreshing,
                                onRefresh = { viewModel.refresh(MainViewModel.Feed.EXPLORE) },
                                pullState = explorePull,
                                contentPadding = listPadding,
                                shelves = viewModel.exploreShelves.collectAsStateWithLifecycle().value
                                    .let { if (hideHomeVideos) it.withoutVideos() else it },
                                onShelfItemClick = { item, shelfTitle ->
                                    val browseId = item.browseId
                                    shelfSong(item)?.let { song ->
                                        playRadio(song, QueueSource(shelfTitle, PlaybackSourceType.EXPLORE))
                                    } ?: browseId?.let {
                                        viewModel.openDetail(
                                            browseId = it,
                                            title = item.title,
                                            subtitle = item.subtitle,
                                            thumbnailUrl = item.thumbnailUrl,
                                        )
                                    }
                                },
                                onShelfItemLongPress = onShelfLongPress,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                            )
                            TAB_RADIO -> RadioScreen(
                                playingId = player.song?.videoId,
                                isPlaying = player.isPlaying,
                                onPlay = { station ->
                                    playRequestGeneration.intValue++
                                    activeRadioSeed.value = null
                                    scope.launch {
                                        if (refusedByHost()) return@launch
                                        controller?.playSongs(listOf(station.toSong()), 0)
                                        RadioBrowser.countClick(station.id)
                                    }
                                },
                                contentPadding = listPadding,
                                listState = radioListState,
                            )
                            TAB_PODCASTS -> {
                                LaunchedEffect(Unit) { viewModel.loadPodcasts() }
                                MoodGenrePlaylistsScreen(
                                    title = podcastsLabel,
                                    state = viewModel.podcasts.collectAsStateWithLifecycle().value,
                                    listState = podcastsListState,
                                    onItemClick = { item ->
                                        shelfSong(item)?.let { song ->
                                            playRadio(song, QueueSource(podcastsLabel, PlaybackSourceType.EXPLORE))
                                        } ?: item.browseId?.let { browseId ->
                                            viewModel.openDetail(
                                                browseId = browseId,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                thumbnailUrl = item.thumbnailUrl,
                                            )
                                        }
                                    },
                                    onRetry = { viewModel.loadPodcasts(force = true) },
                                    contentPadding = listPadding,
                                )
                            }
                            TAB_SEARCH -> SearchScreen(
                                query = query,
                                onQueryChange = viewModel::onQueryChange,
                                filter = filter,
                                currentSong = player.song,
                                isPlaying = player.isPlaying,
                                onFilterChange = viewModel::onFilterChange,
                                results = results,
                                youtubeSearch = youtubeSearch,
                                loadingMore = searchLoadingMore,
                                onLoadMore = viewModel::loadMoreSearchResults,
                                listState = searchListState,
                                scrollResetTrigger = searchScrollReset,
                                focusRequested = searchFocusRequested,
                                onFocusHandled = { searchFocusRequested = false },
                                onSongClick = { songs, index ->
                                    songs.getOrNull(index)?.let { song ->
                                        viewModel.recordEntity(SearchHistoryEntity(
                                            id = song.videoId,
                                            title = song.title,
                                            subtitle = song.artist.ifEmpty { "" },
                                            artworkUrl = song.thumbnailUrl,
                                            entityType = EntityType.TRACK,
                                        ))
                                        playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                                    }
                                },
                                onSongLongPress = openSongMenu,
                                onSongSwipe = onSongSwipe,
                                onTopResultPlay = { song ->
                                    viewModel.recordEntity(SearchHistoryEntity(
                                        id = song.videoId,
                                        title = song.title,
                                        subtitle = song.artist.ifEmpty { "" },
                                        artworkUrl = song.thumbnailUrl,
                                        entityType = EntityType.TRACK,
                                    ))
                                    playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                                },
                                onTopResultPlaylist = { song ->
                                    viewModel.recordEntity(SearchHistoryEntity(
                                        id = song.videoId,
                                        title = song.title,
                                        subtitle = song.artist.ifEmpty { "" },
                                        artworkUrl = song.thumbnailUrl,
                                        entityType = EntityType.TRACK,
                                    ))
                                    viewModel.loadPlaylists()
                                    playlistTarget = song
                                },
                                onBrowseClick = { item ->
                                    viewModel.recordEntity(SearchHistoryEntity(
                                        id = item.browseId ?: "",
                                        title = item.title,
                                        subtitle = item.subtitle.ifBlank { "" },
                                        artworkUrl = item.thumbnailUrl,
                                        entityType = when (item.type) {
                                            BrowseType.ALBUM -> EntityType.ALBUM
                                            BrowseType.ARTIST -> EntityType.ARTIST
                                            BrowseType.PLAYLIST -> EntityType.PLAYLIST
                                            BrowseType.OTHER -> EntityType.OTHER
                                        },
                                    ))
                                    viewModel.openDetail(
                                        browseId = item.browseId,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        type = item.type,
                                    )
                                },
                                onBrowseLongPress = { item ->
                                    if (item.type != BrowseType.ARTIST) {
                                        openBrowseMenu(
                                            BrowseTarget(
                                                browseId = item.browseId,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                thumbnailUrl = item.thumbnailUrl,
                                                type = item.type,
                                                downloadId = downloadIdFor(item.browseId),
                                            ),
                                        )
                                    }
                                },
                                history = searchHistory,
                                suggestions = searchSuggestions,
                                typeaheadResults = viewModel.typeaheadResults.collectAsStateWithLifecycle().value,
                                onSubmit = viewModel::submitSearch,
                                onSuggestionClick = viewModel::searchFor,
                                onHistoryClick = { entity ->
                                    when (entity.entityType) {
                                        EntityType.TRACK -> {
                                            playRadio(
                                                com.bxane.nuvx.data.model.Song(
                                                    videoId = entity.id,
                                                    title = entity.title,
                                                    artist = entity.subtitle,
                                                    thumbnailUrl = entity.artworkUrl,
                                                ),
                                                QueueSource(entity.title, PlaybackSourceType.SEARCH),
                                            )
                                        }
                                        EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST, EntityType.OTHER -> {
                                            viewModel.openDetail(
                                                browseId = entity.id,
                                                title = entity.title,
                                                subtitle = entity.subtitle,
                                                thumbnailUrl = entity.artworkUrl,
                                            )
                                        }
                                    }
                                },
                                onHistoryRemove = viewModel::removeSearch,
                                onHistoryClear = viewModel::clearSearchHistory,
                                onTypeaheadLongPress = openSongMenu,
                                contentPadding = listPadding,
                                topPadding = topBarHeight() + 4.dp,
                                showField = false,
                                sourceSwitcher = {
                                    SegmentedControl(
                                        options = listOf(
                                            stringResource(R.string.search_source_youtube),
                                            stringResource(R.string.library),
                                            stringResource(R.string.search_source_addons),
                                        ),
                                        selectedIndex = searchSource.ordinal,
                                        onSelect = { index -> onSearchSourceChange(SearchSource.entries[index]) },
                                    )
                                },
                                searchingLibrary = searchSource == SearchSource.LIBRARY,
                                categories = searchCategories(viewModel),
                                onCategoryClick = { category ->
                                    selectedTab = TAB_EXPLORE
                                    viewModel.openMoodGenre(category)
                                },
                                libraryResults = libraryResults,
                                searchingAddons = searchSource == SearchSource.ADDONS,
                                addonResults = addonResults,
                                isBlocked = { song ->
                                    val configId = SourceRegistry.parseTrackKey(song.videoId)?.first
                                    !SourceRegistry.permitsForPlayback(configId, addonCeiling)
                                },
                                onBlockedSongClick = { song -> blockedAddonSong = song },
                                addonHistory =viewModel.addonSearchHistory.collectAsStateWithLifecycle().value,
                                onAddonHistoryClick = { term ->
                                    viewModel.onQueryChange(term)
                                    viewModel.submitSearch()
                                },
                                onAddonHistoryRemove = viewModel::removeAddonSearch,
                                onAddonHistoryClear = viewModel::clearAddonSearchHistory,
                                onLibrarySongClick = { songs, index ->
                                    playFrom(
                                        songs,
                                        index,
                                        if (searchSource == SearchSource.ADDONS) {
                                            QueueSource(
                                                context.getString(R.string.search_source_addons),
                                                PlaybackSourceType.BROWSE,
                                                "addons:search",
                                            )
                                        } else {
                                            QueueSource(
                                                context.getString(R.string.local_music),
                                                PlaybackSourceType.BROWSE,
                                                "local:all",
                                            )
                                        },
                                    )
                                },
                            )
                            else -> LibraryScreen(
                                signedIn = signedIn,
                                state = libraryState,
                                listState = libraryListState,
                                onShelfItemClick = onLibraryItemClick,
                                onShelfItemLongPress = onBrowseLongPress,
                                onNewPlaylist = { creatingPlaylist = true },
                                onImportSpotifyPlaylist = { showSpotifyImportDialog = true },
                                onShowAll = { shelf -> navigator.push(AppRoute.LibraryShowAll(shelf)) },
                                replay = {
                                    LibraryReplayEntry(
                                        cards = replayCards,
                                        loading = replay.loading,
                                        holder = account?.name.orEmpty(),
                                        memberSince = replay.memberSince,
                                        onOpenReplay = { page ->
                                            replayLandingPage = page
                                            navigator.push(AppRoute.Replay)
                                        },
                                    )
                                },
                                onSignIn = { webSession = WebSessionMode.SIGN_IN },
                                onRetry = viewModel::loadLibrary,
                                refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                                onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
                                pullState = libraryPull,
                                contentPadding = listPadding,
                                links = libraryLinks(),
                                deviceItems = libraryDeviceItems(downloadedReleases) + localPlaylistItems,
                            )
                        }
                    }
                    val barsMotion = artworkMotion && transition.targetState == EnterExitState.Visible
                    CompositionLocalProvider(LocalPlayingBarsMotion provides barsMotion) {
                        if (destination is AppDestination.Tab) {
                            pageContent()
                        } else {
                            pageKeysSeen += key
                            pageStates.SaveableStateProvider(key, pageContent)
                        }
                    }
                }

                val isDetailVisible = detail != null &&
                    (detail.type == BrowseType.ALBUM ||
                        detail.type == BrowseType.PLAYLIST ||
                        detail.type == BrowseType.ARTIST) &&
                    !isLocalDetail
                val chromePageColor = if (isDetailVisible) {
                    detailPalette.background
                } else {
                    MaterialTheme.colorScheme.background
                }
                val artistListState = detail
                    ?.takeIf { isDetailVisible && it.type == BrowseType.ARTIST }
                    ?.let { detailListStates.getOrPut(it.browseId) { LazyListState() } }
                val homeHeadingVisible = route == null && detail == null && selectedTab == TAB_HOME
                val headerListState = artistListState ?: homeListState.takeIf { homeHeadingVisible }
                val fadeStart = if (artistListState != null) TOP_FADE_START else 0f
                val fadeRamp = if (artistListState != null) TOP_FADE_RAMP else 1f
                val topFadeAlpha by remember(headerListState, fadeStart, fadeRamp) {
                    derivedStateOf {
                        val list = headerListState ?: return@derivedStateOf 1f
                        if (list.firstVisibleItemIndex > 0) return@derivedStateOf 1f
                        val header = list.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }?.size?.toFloat()
                            ?: return@derivedStateOf 0f
                        ((list.firstVisibleItemScrollOffset - header * fadeStart) /
                            (header * fadeRamp)).coerceIn(0f, 1f)
                    }
                }
                if (reduceDynamicBlur || !glassActive || !progressiveBlur) {
                    BottomFadeScrim(
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .rotate(180f)
                            .graphicsLayer { alpha = topFadeAlpha },
                    )
                } else {
                    TopFadeBlur(
                        hazeState = hazeState,
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = topFadeAlpha },
                    )
                }

                val classicSearch = classicSearchOn()
                val searchHere = selectedTab == TAB_SEARCH && route == null && detail == null && selectedMoodGenre == null
                val searchField: @Composable () -> Unit = {
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(searchFocusRequested) {
                        if (searchFocusRequested) {
                            searchFieldFocus.requestFocus()
                            keyboard?.show()
                            searchFocusRequested = false
                        }
                    }
                    SearchField(
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        onSubmit = viewModel::submitSearch,
                        focusRequester = searchFieldFocus,
                        placeholder = org.jetbrains.compose.resources.stringResource(
                            if (searchSource == SearchSource.LIBRARY) {
                                SharedRes.string.search_library_hint
                            } else if (searchSource == SearchSource.ADDONS) {
                                SharedRes.string.search_addons_hint
                            } else {
                                SharedRes.string.search_hint
                            },
                        ),
                    )
                }

                FrostedTopBar(
                    title = when (route) {
                        AppRoute.Spotify -> stringResource(R.string.spotify)
                        AppRoute.Downloads -> stringResource(R.string.downloads)
                        AppRoute.Discord -> "Discord"
                        AppRoute.History -> stringResource(R.string.history)
                        is AppRoute.LibraryShowAll -> route.shelf.title
                        is AppRoute.ArtistShelf -> route.shelf.title
                        AppRoute.Moods -> stringResource(R.string.find_your_mood)
                        AppRoute.AccountScrobbling -> stringResource(R.string.account_scrobbling)
                        AppRoute.Sources -> stringResource(R.string.sources)
                        AppRoute.ListenTogether -> stringResource(R.string.listen_together)
                        AppRoute.WearOs -> stringResource(R.string.wear_os_page_title)
                        AppRoute.Equalizer -> stringResource(R.string.equalizer)
                        AppRoute.Settings -> stringResource(R.string.settings)
                        is AppRoute.SettingsSection -> stringResource(route.category.title)
                        is AppRoute.SettingsChoice -> stringResource(route.choice.title)
                        AppRoute.EqualizerPresets -> stringResource(R.string.equalizer_preset)
                        AppRoute.Replay -> stringResource(R.string.replay)
                        null -> when {
                            detail != null && detailActiveShelf != null -> detailActiveShelf?.title.orEmpty()
                            detail != null -> detail.title
                            selectedMoodGenre != null -> selectedMoodGenre?.title.orEmpty()
                            else -> tabs[selectedTab].let {
                                if (it.label == "Play") stringResource(R.string.listen_now) else it.label
                            }
                        }
                    },
                    transparentBackdrop = true,
                    artworkPageChrome = true,
                    backButtonHazeState = hazeState,
                    trailingTitle = if (detail != null && detailActiveShelf != null) detail.title else null,
                    scrolled = {
                        when {
                            route == AppRoute.Replay -> replayScrolled
                            route != null -> true
                            detail != null && detailActiveShelf != null -> true
                            detail != null -> detailScrolled
                            selectedMoodGenre != null -> true
                            else -> scrolled || selectedTab == TAB_SEARCH
                        }
                    },
                    refreshing = currentFeed != null && currentFeed in refreshing,
                    pullFraction = { currentPull?.distanceFraction ?: 0f },
                    onBack = when {
                        route != null || detail != null || selectedMoodGenre != null -> goBack
                        else -> null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                    accessory = if (searchHere && classicSearch) searchField else null,
                    leading = if (route == null && detail == null && selectedTab == TAB_HOME && !diwaliFestive) {
                        { TracedMark() }
                    } else null,
                    actions = {
                        if (route == AppRoute.ListenTogether) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                when (val state = partyServerStatus) {
                                    is ServerConnectionState.CustomFallback -> {
                                        Icon(
                                            SfIcons.CloudOff,
                                            contentDescription = stringResource(R.string.listen_together_top_bar_fallback, stringResource(R.string.listen_together_ping, state.latencyMs)),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.listen_together_ping, state.latencyMs),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                        )
                                    }
                                    is ServerConnectionState.CustomOnline -> {
                                        Text(
                                            text = stringResource(R.string.listen_together_ping, state.latencyMs),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                    }
                                    is ServerConnectionState.DefaultOnline -> {
                                        Text(
                                            text = stringResource(R.string.listen_together_ping, state.latencyMs),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                    }
                                    ServerConnectionState.Checking -> {
                                        Text(
                                            text = stringResource(R.string.listen_together_server_checking),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    ServerConnectionState.Offline -> {
                                        Text(
                                            text = stringResource(R.string.listen_together_server_offline_dash),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                        if (route == null && detail == null && selectedTab == TAB_HOME) {
                            updateNotice?.let { update ->
                                IconButton(onClick = { showUpdateDialog = true }) {
                                    Icon(
                                        SfIcons.Upgrade,
                                        contentDescription = stringResource(R.string.update_available, update.version),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (!(route != null && AppRoute.Settings in navigator)) {
                            if (route == null && detail == null && selectedTab == TAB_LIBRARY) {
                                IconButton(
                                    onClick = {
                                        navigator.push(AppRoute.History)
                                        viewModel.loadHistory()
                                    },
                                ) {
                                    Icon(
                                        SfIcons.History,
                                        contentDescription = stringResource(R.string.listening_history),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            if (route is AppRoute.LibraryShowAll) {
                                Box {
                                    IconButton(onClick = { librarySortMenuOpen = true }) {
                                        Icon(
                                            SfIcons.Sort,
                                            contentDescription = stringResource(R.string.sort_library),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = librarySortMenuOpen,
                                        onDismissRequest = { librarySortMenuOpen = false },
                                    ) {
                                        LibrarySort.entries.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.localizedLabel()) },
                                                trailingIcon = if (option == librarySort) {
                                                    { Icon(SfIcons.Check, contentDescription = null) }
                                                } else null,
                                                onClick = {
                                                    AppSettings.setLibrarySort(option)
                                                    librarySortMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if ((isReleaseDetail || isLocalDetail) && detail != null) {
                                val releaseSongs = (detail.songs as? UiState.Success)?.data.orEmpty()
                                if (releaseSongs.isNotEmpty()) {
                                    IconButton(onClick = { detailSearching = !detailSearching }) {
                                        Icon(
                                            if (detailSearching) SfIcons.Close else SfIcons.Search,
                                            contentDescription = stringResource(
                                                if (detailSearching) R.string.close_search else R.string.search_this_list,
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { if (isLocalDetail) folderMenuOpen = true else songSortMenuOpen = true },
                                ) {
                                    Icon(
                                        SfIcons.Sort,
                                        contentDescription = stringResource(
                                            if (isLocalDetail) R.string.sort_music else R.string.sort_songs,
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (isReleaseDetail && releaseSongs.isNotEmpty()) {
                                    IconButton(
                                        modifier = Modifier.menuAnchor(),
                                        onClick = {
                                            openBrowseMenu(BrowseTarget(
                                                browseId = detail.browseId,
                                                title = detail.title,
                                                subtitle = detail.subtitle,
                                                thumbnailUrl = detail.thumbnailUrl,
                                                type = detail.type,
                                                songs = releaseSongs.sortedForDetail(songSort).map { song ->
                                                    if (detail.type == BrowseType.ALBUM) {
                                                        song.copy(albumName = song.albumName ?: detail.title)
                                                    } else {
                                                        song
                                                    }
                                                },
                                                fromCard = false,
                                                downloadId = downloadIdFor(detail.browseId),
                                            ))
                                        },
                                    ) {
                                        Icon(
                                            SfIcons.MoreHoriz,
                                            contentDescription = stringResource(R.string.more),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            if (detail != null && !isLocalDetail && detail.type == BrowseType.ARTIST &&
                                detailActiveShelf == null && detail.browseId.startsWith("UC")
                            ) {
                                IconButton(
                                    onClick = {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "https://music.youtube.com/channel/${detail.browseId}",
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, detail.title))
                                    },
                                ) {
                                    Icon(
                                        SfIcons.IosShare,
                                        contentDescription = stringResource(R.string.share),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            TopBarRecognizeButton(visible = route == null && detail == null) { showRecognizer = true }
                            if (route != AppRoute.Downloads) {
                                TopBarDownloadButton(onClick = { navigator.push(AppRoute.Downloads) })
                            }
                            if (!isReleaseDetail) TopBarAccountButton(
                                account = account,
                                onClick = {
                                    if (signedIn) {
                                        viewModel.loadChannels()
                                        showAccountSelector = true
                                    } else navigator.push(AppRoute.Settings)
                                },
                                onSwipeProfile = { forward -> viewModel.stepProfile(forward) },
                            )
                        }
                    },
                )

                BottomFadeScrim(
                    withMiniPlayer = player.song != null,
                    pageColor = chromePageColor,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                val floatingBarsHeight = remember { mutableIntStateOf(0) }
                FloatingBarsTapGuard(
                    barsHeight = { floatingBarsHeight.intValue },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                val onTabSelected: (Int) -> Unit = { index ->
                    navigator.clear()
                    viewModel.closeMoodGenre()
                    if (index == TAB_SEARCH && selectedTab != TAB_SEARCH) tabBeforeSearch = selectedTab
                    selectedTab = index

                    if (index == TAB_SEARCH) {
                        viewModel.onQueryChange("")
                        searchFocusRequested = true
                    }
                }

                val searchDock: (@Composable () -> Unit)? = if (searchHere && !classicSearch) {
                    {
                        val back = navTabIds.indexOf(tabBeforeSearch).takeIf { it >= 0 } ?: 0
                        SearchDock(
                            backTab = navTabs[back],
                            onBack = { onTabSelected(navTabIds[back]) },
                            hazeState = hazeState.takeIf { !glassActive },
                            field = searchField,
                        )
                    }
                } else null

                if (glassActive) Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                ) {
                    QueueActionNoticeHost(queueNotice)
                    GlassNavBar(
                        tabs = navTabs,
                        selectedIndex = navTabIds.indexOf(selectedTab),
                        onTabSelected = { onTabSelected(navTabIds[it]) },
                        scrollConnection = navBarScroll,
                        song = player.song,
                        isPlaying = player.isPlaying,
                        isLoading = playPauseBusy,
                        onPlayPause = {
                            togglePlayPause()
                        },
                        onNext = { controller?.seekToNextMediaItem() },
                        onPrevious = { controller?.seekToPrevious() },
                        onExpand = if (reducePlayerMotion) {
                            { showNowPlaying = true }
                        } else {
                            playerSheetMotion::open
                        },
                        controlsLocked = controlsLocked,
                        onBlockedControl = showHostOnlyNotice,
                        dock = activeDock,
                        pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                        modifier = Modifier.fillMaxWidth(),
                        searchDock = searchDock,
                    )
                } else Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { floatingBarsHeight.intValue = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QueueActionNoticeHost(queueNotice)
                    player.song?.let { song ->
                        MiniPlayer(
                            song = song,
                            isPlaying = player.isPlaying,
                            isLoading = playPauseBusy,
                            hazeState = hazeState,
                            onPlayPause = {
                                togglePlayPause()
                            },
                            onNext = { controller?.seekToNextMediaItem() },
                            onPrevious = { controller?.seekToPrevious() },
                            onExpand = if (reducePlayerMotion) {
                                { showNowPlaying = true }
                            } else {
                                playerSheetMotion::open
                            },
                            controlsLocked = controlsLocked,
                            onBlockedControl = showHostOnlyNotice,
                            dock = activeDock,
                            pull = playerSheetMotion.takeIf { !reducePlayerMotion },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    FloatingBottomBar(
                        tabs = navTabs,
                        selectedIndex = navTabIds.indexOf(selectedTab),
                        hazeState = hazeState,
                        onTabSelected = { onTabSelected(navTabIds[it]) },
                        searchDock = searchDock,
                    )
                }
            }

        }

        val playerRaised = showNowPlaying && playerSong != null

        Isolated {
            val pageMenuUp =
                (songActions != null && songMenuOrigin == null && !(menuFromPlayer && playerRaised)) ||
                    (browseActions != null && browseMenuOrigin == null && !browseRenameInSheet)
            LaunchedEffect(pageMenuUp, reduceDynamicBlur) {
                if (pageMenuUp && !reduceDynamicBlur && isGlassSupported()) {
                    pageBackdrop.capture()
                } else {
                    delay(SNAPSHOT_RELEASE_DELAY_MS)
                    pageBackdrop.release()
                }
            }
        }

        val songMenuBody: @Composable (Song) -> Unit = { heldSong ->
            val fromPlayer = menuFromPlayer
            val needsLinks = !fromPlayer && heldSong.localUri == null &&
                heldSong.videoId.isNotBlank() &&
                (heldSong.albumId == null || heldSong.artistId == null)
            var menuLinks by remember(heldSong.videoId) { mutableStateOf<Song?>(null) }
            var menuLinksLoading by remember(heldSong.videoId) { mutableStateOf(needsLinks) }
            LaunchedEffect(heldSong.videoId, needsLinks) {
                if (!needsLinks) return@LaunchedEffect
                menuLinksLoading = true
                menuLinks = YtMusicRepository.trackLinks(heldSong.videoId).getOrNull()
                menuLinksLoading = false
            }
            val song = menuLinks?.takeIf { needsLinks && it.videoId == heldSong.videoId }?.let { extra ->
                heldSong.copy(
                    artistId = heldSong.artistId ?: extra.artistId,
                    albumId = heldSong.albumId ?: extra.albumId,
                    albumName = heldSong.albumName ?: extra.albumName,
                )
            } ?: heldSong
            val share: () -> Unit = {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.videoId}")
                }
                context.startActivity(Intent.createChooser(sendIntent, song.title))
                songActions = null
            }
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                dismissPlayer()
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(id, title, sub, art, type)
            }
            val versionEffectiveSong = playbackVersionSwitcher.versionEffectiveSong(song)
            val menuIsAudioVersion = playbackVersionSwitcher.isAudioVersion(song)
            val onToggleVersion: (() -> Unit)? = if (
                controller?.currentMediaItem?.mediaId == song.videoId
            ) {
                toggle@{
                    songActions = null
                    playbackVersionSwitcher.toggleVersion(song, versionEffectiveSong, partyState.inParty)
                }
            } else {
                null
            }
            LaunchedEffect(song.videoId) { viewModel.loadSongMenu(song.videoId) }
            val editable = viewModel.editablePlaylist(detail?.browseId)
                ?.takeIf { !fromPlayer && song.setVideoId != null }
            val automixOn by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
            val cacheVersion by AudioCache.contentsChanged.collectAsStateWithLifecycle()
            val inCache by produceState(false, song.videoId, cacheVersion) {
                value = song.localUri == null &&
                    withContext(Dispatchers.IO) { AudioCache.holds(song.videoId) }
            }
            SongActionsMenu(
                song = song,
                signedIn = signedIn,
                likeStatus = likeStatusOf(song),
                onPlayNext = { playNext(song); songActions = null },
                onAddToQueue = { addToQueue(song); songActions = null },
                onStartRadio = { startRadio(song); songActions = null },
                onDownload = {
                    if (!fromPlayer && detail?.browseId == CACHE_FOLDER_BROWSE_ID) {
                        downloadFromCache(listOf(song))
                    } else {
                        downloadSong(song)
                    }
                },
                onUploadToWebDav =
                    if (com.bxane.nuvx.data.webdav.WebDavConfig.isConfigured(webdavUrl) &&
                        com.bxane.nuvx.data.webdav.WebDavUploads.isUploadable(song)
                    ) {
                        {
                            songActions = null
                            uploadToWebDav(listOf(song))
                        }
                    } else {
                        null
                    },
                onToggleLike = { toggleLikeOf(song) },
                onToggleDislike = {
                    val previousStatus = viewModel.toggleDislike(song.videoId)
                    if (
                        previousStatus != null &&
                        shouldSkipAfterDislike(
                            previousStatus = previousStatus,
                            targetVideoId = song.videoId,
                            currentVideoId = player.song?.videoId,
                        )
                    ) {
                        controller?.seekToNextMediaItem()
                    }
                },
                onAddToPlaylist = {
                    songActions = null
                    if (song.localUri != null && (song.videoId.startsWith("content://") || song.videoId.startsWith("file://"))) {
                        offlinePlaylistTarget = song
                    } else {
                        viewModel.loadPlaylists()
                        playlistTarget = song
                    }
                },
                onRemoveFromPlaylist = editable?.let {
                    {
                        songActions = null
                        viewModel.removeFromPlaylist(it.browseId, song)
                    }
                } ?: detail?.browseId
                    ?.takeIf { !fromPlayer && song.localUri != null && OfflinePlaylists.isOfflinePlaylist(it) }
                    ?.let { id ->
                        {
                            songActions = null
                            OfflinePlaylists.remove(id, song)
                        }
                    },
                onRemoveFromCache = if (inCache) {
                    {
                        songActions = null
                        removeFromCache(listOf(song))
                    }
                } else {
                    null
                },
                onRemoveFromHistory = song.feedbackToken?.takeIf { menuFromHistory }?.let { token ->
                    {
                        songActions = null
                        viewModel.removeFromHistory(token, onFailure = {
                            Toast.makeText(context, R.string.remove_from_history_failed, Toast.LENGTH_SHORT).show()
                        })
                    }
                },
                onOpenAlbum = { id ->
                    openPage(
                        id,
                        song.albumName ?: song.title,
                        song.artist,
                        BrowseType.ALBUM,
                    )
                },
                onOpenArtist = { id ->
                    openPage(id, song.artist, context.getString(R.string.artist), BrowseType.ARTIST)
                },
                resolvingLinks = if (fromPlayer) linksLoading else needsLinks && menuLinksLoading,
                showSleepTimer = fromPlayer,
                onRollbackToOriginal = if (fromPlayer &&
                    song.hasYouTubeOriginal() &&
                    song.localUri == null &&
                    song.videoId !in pinnedToOriginal &&
                    !playingYouTubesOwn(song.videoId, controller) &&
                    controller?.currentMediaItem?.mediaId == song.videoId
                ) {
                    {
                        controller?.revertToOriginal()
                        songActions = null
                    }
                } else {
                    null
                },
                onUpgradeQuality = if (fromPlayer &&
                    (
                        song.videoId in pinnedToOriginal ||
                            playingYouTubesOwn(song.videoId, controller)
                        ) &&
                    song.localUri == null &&
                    controller?.currentMediaItem?.mediaId == song.videoId
                ) {
                    {
                        controller.upgradeQuality()
                        songActions = null
                    }
                } else {
                    null
                },
                upgradeQualityInProgress = fromPlayer && song.videoId in qualityUpgradesInFlight,
                onToggleAudioVersion = onToggleVersion,
                isAudioVersion = menuIsAudioVersion,
                audioVersionMissing = song.videoId in missingVersions,
                onShare = share.takeIf { song.videoId.isNotBlank() },
                onCopyLog = if (fromPlayer) {
                    {
                        songActions = null
                        scope.launch {
                            val text = TrackLog.forTrack(song, NerdStats.current.value)
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.log_copied_line_count,
                                    text.lineSequence().count(),
                                    text.lineSequence().count(),
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                } else {
                    null
                },
                onLyricsOffset = if (fromPlayer) {
                    {
                        songActions = null
                        showLyricsOffset = true
                    }
                } else {
                    null
                },
                onAutomixOverview = if (fromPlayer && automixOn) {
                    {
                        songActions = null
                        MixViewRequest.open = true
                    }
                } else {
                    null
                },
            )
        }
        if (playerRaised) {
            val sheetDensity = LocalDensity.current
            val confirmSheetValue: (SheetValue) -> Boolean = { value ->
                if (value == SheetValue.Hidden && !reducePlayerMotion) {
                    playerSheetMotion.close()
                    false
                } else {
                    true
                }
            }
            val nowPlayingSheetState = rememberSaveable(
                saver = SheetState.Saver(
                    skipPartiallyExpanded = true,
                    confirmValueChange = confirmSheetValue,
                    positionalThreshold = { with(sheetDensity) { 56.dp.toPx() } },
                    velocityThreshold = { with(sheetDensity) { 125.dp.toPx() } },
                    skipHiddenState = false,
                ),
            ) {
                SheetState(
                    skipPartiallyExpanded = true,
                    positionalThreshold = { with(sheetDensity) { 56.dp.toPx() } },
                    velocityThreshold = { with(sheetDensity) { 125.dp.toPx() } },
                    initialValue = if (playerSheetMotion.holding) SheetValue.Expanded else SheetValue.Hidden,
                    confirmValueChange = confirmSheetValue,
                )
            }
            DisposableEffect(nowPlayingSheetState) {
                playerSheetMotion.sheet = nowPlayingSheetState
                playerDock.sheetOffset = playerSheetMotion::position
                onDispose {
                    playerDock.sheetOffset = null
                    playerSheetMotion.onSheetGone()
                }
            }
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                sheetState = nowPlayingSheetState,
                scrimColor = if (reducePlayerMotion) BottomSheetDefaults.ScrimColor else Color.Transparent,
                shape = PlayerSheetShape,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                sheetMaxWidth = Dp.Unspecified,
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = reducePlayerMotion),
            ) {
                BackHandler(enabled = !reducePlayerMotion) { playerSheetMotion.close() }
                val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                DacVolumeKeys()
                DisposableEffect(sheetWindow, reducePlayerMotion) {
                    if (!reducePlayerMotion) playerSheetMotion.attachWindow(sheetWindow)
                    onDispose { }
                }
                Box(
                    Modifier
                        .offset { IntOffset(0, playerSheetMotion.contentOffsetPx()) }
                        .drawBehind {
                            val open = playerDock.openFraction()
                            if (!reducePlayerMotion && open > 0f && !(open >= 1f && playerDock.attached)) {
                                val top = playerSheetMotion.position().takeUnless { it.isNaN() } ?: 0f
                                drawRect(
                                    color = Color.Black.copy(alpha = PLAYER_SCRIM_ALPHA * open),
                                    topLeft = Offset(0f, -top.toInt().toFloat()),
                                    size = Size(size.width, size.height),
                                )
                            }
                        }
                        .guardSheetFromContentTouches(nowPlayingSheetState)
                        .onSizeChanged {
                            playerDock.sheetTravel = it.height.toFloat()
                            playerSheetMotion.laidOut = true
                        },
                ) {
                    val playerMenuUp = songActions != null && songMenuOrigin == null && menuFromPlayer
                    LaunchedEffect(playerMenuUp, reduceDynamicBlur) {
                        if (!playerMenuUp || reduceDynamicBlur) {
                            delay(SNAPSHOT_RELEASE_DELAY_MS)
                            playerBackdrop.release()
                        }
                    }
                    Box(
                        when {
                            reduceDynamicBlur -> Modifier
                            playerLiveCanvas -> Modifier.layerBackdrop(livePlayerBackdrop)
                            else -> Modifier.snapshotBackdropSource(playerBackdrop)
                        },
                    ) {
                        CompositionLocalProvider(LocalPlayerDock provides activeDock) {
                            nowPlaying(playerSong)
                        }
                    }
                    IosMenuPopover(
                        item = songActions?.takeIf { songMenuOrigin == null && menuFromPlayer },
                        anchor = songMenuAnchor,
                        onDismiss = { songActions = null },
                        backdrop = if (playerLiveCanvas) livePlayerBackdrop else playerBackdrop,
                    ) { song ->
                        songMenuBody(song)
                    }
                }
            }
        }

        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card ->
                        replaySharePage = card
                        showReplayShare = true
                    },
                    paused = showReplayShare,
                )
            }
        }

        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = account?.name.orEmpty(),
                        memberSince = replay.memberSince,
                        page = replaySharePage,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }

        Isolated {
            HeldContextMenu(
                item = songActions,
                held = songMenuOrigin,
                hazeState = hazeState,
                onDismiss = { songActions = null },
                onPreviewClick = { song ->
                    song.albumId?.let { id ->
                        songActions = null
                        viewModel.openDetail(id, song.albumName ?: song.title, song.artist, song.thumbnailUrl, BrowseType.ALBUM)
                    }
                },
            ) { song ->
                songMenuBody(song)
            }
            LaunchedEffect(playerRaised) {
                if (!playerRaised && menuFromPlayer) songActions = null
            }
            IosMenuPopover(
                item = songActions?.takeIf { songMenuOrigin == null && !(menuFromPlayer && playerRaised) },
                anchor = songMenuAnchor,
                onDismiss = { songActions = null },
                backdrop = pageBackdrop,
                hazeState = hazeState,
            ) { song ->
                songMenuBody(song)
            }
            LaunchedEffect(songActions == null) {
                if (songActions == null) {
                    songMenuOrigin = null
                    songMenuAnchor = null
                    menuFromHistory = false
                }
            }
        }

        if (playlistTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            PlaylistPickerSheet(
                playlists = playlists,
                loading = playlistsLoading,
                song = target,
                startCreating = target == null,
                onDismiss = dismiss,
                onAdd = { picked ->
                    target?.let { song ->
                        viewModel.addToPlaylists(picked, song) { added, alreadyThere, failed ->
                            showQueueNotice(
                                when {
                                    added > 1 -> context.resources.getQuantityString(
                                        R.plurals.added_to_playlists_notice, added, added,
                                    )
                                    added == 1 -> context.getString(R.string.song_added_to_playlist)
                                    alreadyThere > 0 && failed == 0 ->
                                        context.getString(R.string.song_already_in_playlist)
                                    else -> context.getString(R.string.failed)
                                },
                            )
                        }
                    }
                },
                onCreate = { title, privacy ->
                    viewModel.createPlaylist(title, privacy, target)
                },
            )
        }

        if (offlinePlaylistTarget != null || creatingOfflinePlaylist) {
            val target = offlinePlaylistTarget
            val dismiss = {
                offlinePlaylistTarget = null
                creatingOfflinePlaylist = false
            }
            val choices = remember(offlinePlaylists) {
                offlinePlaylists.map { playlist ->
                    UserPlaylist(
                        playlistId = playlist.id,
                        title = playlist.title,
                        subtitle = context.resources.getQuantityString(
                            R.plurals.song_count_plural,
                            playlist.songs.size,
                            playlist.songs.size,
                        ),
                        thumbnailUrl = playlist.songs.firstNotNullOfOrNull { it.thumbnailUrl }
                            .takeUnless { playlist.isLiked },
                    )
                }
            }
            PlaylistPickerSheet(
                playlists = choices,
                loading = false,
                song = target,
                startCreating = target == null,
                offline = true,
                onDismiss = dismiss,
                onAdd = { picked ->
                    target?.let { song ->
                        val result = OfflinePlaylists.add(picked.mapTo(HashSet()) { it.playlistId }, listOf(song))
                        showQueueNotice(
                            when {
                                result.added > 1 -> context.resources.getQuantityString(
                                    R.plurals.added_to_playlists_notice, result.added, result.added,
                                )
                                result.added == 1 -> context.getString(R.string.song_added_to_playlist)
                                result.alreadyThere > 0 -> context.getString(R.string.song_already_in_playlist)
                                else -> context.getString(R.string.failed)
                            },
                        )
                    }
                },
                onCreate = { title, _ ->
                    OfflinePlaylists.create(title, listOfNotNull(target))
                    if (target != null) showQueueNotice(context.getString(R.string.song_added_to_playlist))
                },
            )
        }

        if (showSpotifyImportDialog) {
            BackHandler { showSpotifyImportDialog = false }
            SpotifyImportAlert(
                hazeState = hazeState,
                signedIn = signedIn,
                onImported = { title, privacy, songs ->
                    viewModel.createPlaylistWithVideoIds(
                        title,
                        privacy,
                        songs.map { it.videoId },
                        songs,
                    ) { browseId, pTitle, savedLocally, failedSongs ->
                        showQueueNotice(
                            when {
                                failedSongs > 0 -> context.getString(R.string.spotify_import_partial, pTitle, failedSongs)
                                savedLocally && signedIn -> context.getString(R.string.spotify_import_local_fallback, pTitle)
                                else -> context.getString(R.string.spotify_import_done, pTitle)
                            },
                        )
                        browseId?.let { id ->
                            viewModel.openDetail(id, pTitle, "${songs.size - failedSongs} songs", songs.firstOrNull()?.thumbnailUrl)
                        }
                    }
                },
                onDismiss = { showSpotifyImportDialog = false },
            )
        }

        val browseMenuBody: @Composable (BrowseTarget) -> Unit = { target ->
            val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
                {
                    browseActions = null
                    withBrowseSongs(target, action)
                }
            }
            LaunchedEffect(target.browseId) {
                viewModel.resolvePlaylistOwnership(target.browseId)
            }
            val ownedPlaylists by viewModel.playlistOwned.collectAsStateWithLifecycle()
            val playlist = target.browseId
                ?.takeIf { signedIn && ownedPlaylists[it] == true }
                ?.let { id -> playlists.firstOrNull { it.browseId == id } }
            val remote = target.browseId?.startsWith("local:") == false
            val downloadable = remote ||
                target.browseId?.startsWith("local:playlist:sp_local_") == true
            val pinnedPlaylists by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
            val pinnableId = target.browseId?.takeIf {
                target.type == BrowseType.PLAYLIST &&
                    !it.startsWith(OfflinePlaylists.PREFIX) && !it.startsWith(LocalGroups.PREFIX)
            }
            val offlineEditable = OfflinePlaylists.get(target.browseId)?.takeUnless { it.isLiked }
            BrowseActionsMenu(
                target = target.copy(
                    playlist = playlist ?: offlineEditable?.let {
                        UserPlaylist(playlistId = it.id, title = it.title, subtitle = "", thumbnailUrl = null)
                    },
                ),
                onRenameInSheet = {
                    browseRenameInSheet = true
                    browseMenuOrigin = null
                },
                startRenaming = browseRenameInSheet,
                onPlayNext = act(playSongsNext),
                onAddToQueue = act(addSongsToQueue),
                onPlay = act { songs -> play(songs, 0) }.takeIf { target.fromCard },
                onShuffle = act { songs ->
                    QueueShuffle.enableForNextQueue()
                    play(songs, songs.indices.random())
                }.takeIf { target.fromCard },
                onOpen = target.browseId
                    ?.takeIf { target.fromCard }
                    ?.let { id ->
                        {
                            browseActions = null
                            viewModel.openDetail(
                                browseId = id,
                                title = target.title,
                                subtitle = target.subtitle,
                                thumbnailUrl = target.thumbnailUrl,
                                type = target.type,
                            )
                        }
                    },
                onDownloadAll = act { songs ->
                    startDownload(
                        songs,
                        target.browseId
                            ?.takeIf { target.type != BrowseType.ARTIST }
                            ?.let { id ->
                                DownloadTarget(
                                    id = id,
                                    title = target.title,
                                    subtitle = target.subtitle,
                                    thumbnailUrl = target.thumbnailUrl,
                                    playlist = target.type == BrowseType.PLAYLIST,
                                )
                            },
                    )
                }.takeIf { downloadable },
                onShare = target.browseId
                    ?.takeIf { remote && (target.type == BrowseType.ALBUM || target.type == BrowseType.PLAYLIST) }
                    ?.let { id ->
                        {
                            val url = if (target.type == BrowseType.PLAYLIST) {
                                "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
                            } else {
                                "https://music.youtube.com/browse/$id"
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, target.title))
                            browseActions = null
                        }
                    },
                isPinned = pinnableId != null && pinnableId in pinnedPlaylists,
                onTogglePin = pinnableId?.let { id ->
                    {
                        val nowPinned = AppSettings.togglePinnedPlaylist(id)
                        if (!nowPinned && id !in pinnedPlaylists) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.pinned_playlist_limit,
                                    AppSettings.MAX_PINNED_PLAYLISTS,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        browseActions = null
                    }
                },
                onRename = playlist?.let { p ->
                    { name: String ->
                        browseActions = null
                        viewModel.renamePlaylist(p, name)
                    }
                } ?: offlineEditable?.let { p ->
                    { name: String ->
                        browseActions = null
                        OfflinePlaylists.rename(p.browseId, name)
                        viewModel.retitleDetail(p.browseId, name.trim())
                    }
                },
                onReorder = playlist?.let { p ->
                    {
                        browseActions = null
                        if (detail?.browseId != p.browseId) {
                            viewModel.openDetail(
                                browseId = p.browseId,
                                title = p.title,
                                subtitle = p.subtitle,
                                thumbnailUrl = p.thumbnailUrl,
                                type = BrowseType.PLAYLIST,
                            )
                        }
                        detailSearching = false
                        reorderTarget = p
                        reorderEntries = UiState.Loading
                        reorderSaving = false
                        viewModel.loadPlaylistEntries(p) { result ->
                            if (reorderTarget != p) return@loadPlaylistEntries
                            reorderEntries = result.fold(
                                onSuccess = { UiState.Success(it) },
                                onFailure = { UiState.Error(context.getString(R.string.failed)) },
                            )
                        }
                    }
                },
                onDelete = playlist?.let { p ->
                    {
                        browseActions = null
                        viewModel.deletePlaylist(p)
                    }
                } ?: offlineEditable?.let { p ->
                    {
                        browseActions = null
                        if (detail?.browseId == p.browseId) viewModel.closeDetail()
                        OfflinePlaylists.delete(p.browseId)
                    }
                },
                onDeleteDownload = target.downloadId?.let { id ->
                    {
                        browseActions = null
                        scope.launch { Downloads.deleteCollection(context, id) }
                    }
                },
            )
        }
        Isolated {
            browseActions?.takeIf { browseRenameInSheet }?.let { target ->
                ModalBottomSheet(
                    onDismissRequest = { browseActions = null },
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    browseMenuBody(target)
                }
            }
            HeldContextMenu(
                item = browseActions,
                held = browseMenuOrigin,
                hazeState = hazeState,
                onDismiss = { browseActions = null },
                onPreviewClick = { target ->
                    target.browseId?.let { id ->
                        browseActions = null
                        viewModel.openDetail(
                            browseId = id,
                            title = target.title,
                            subtitle = target.subtitle,
                            thumbnailUrl = target.thumbnailUrl,
                            type = target.type,
                        )
                    }
                },
            ) { target ->
                browseMenuBody(target)
            }
            IosMenuPopover(
                item = browseActions?.takeIf { browseMenuOrigin == null && !browseRenameInSheet },
                anchor = browseMenuAnchor,
                onDismiss = { browseActions = null },
                backdrop = pageBackdrop,
                hazeState = hazeState,
            ) { target ->
                browseMenuBody(target)
            }
            LaunchedEffect(browseActions == null) {
                if (browseActions == null) {
                    browseMenuOrigin = null
                    browseMenuAnchor = null
                    browseRenameInSheet = false
                }
            }
        }

        AnimatedVisibility(
            visibleState = onboardingShown,
            enter = EnterTransition.None,
            exit = slideOutVertically(
                tween<IntOffset>(durationMillis = 560, easing = OnboardingDismissEasing),
            ) { it },
        ) {
            OnboardingScreen(
                signedIn = signedIn,
                initialName = remember {
                    ListenTogether.nickname().ifBlank { account?.name.orEmpty() }
                },
                onNameChosen = ListenTogether::setNickname,
                onSignIn = { webSession = WebSessionMode.SIGN_IN },
                onSpotifyLogin = { showSpotifyLogin = true },
                onDiscordLogin = { showDiscordLogin = true },
                onFinish = AppSettings::setOnboardingComplete,
            )
        }

        webSession?.let { mode ->
            BackHandler { webSession = null }
            var captureRequest by remember(mode) { mutableIntStateOf(0) }
            var captureFailed by remember(mode) { mutableStateOf(false) }
            var pageReady by remember(mode) { mutableStateOf(false) }
            var confirmingProfile by remember(mode) { mutableStateOf(false) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { webSession = null }) {
                            Icon(
                                SfIcons.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when (mode) {
                                    WebSessionMode.SIGN_IN -> stringResource(R.string.sign_in_youtube_music)
                                    WebSessionMode.SWITCH_CHANNEL -> stringResource(R.string.choose_profile)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (pageReady) {
                                Text(
                                    text = if (captureFailed) {
                                         stringResource(R.string.profile_unavailable)
                                     } else {
                                         stringResource(R.string.switch_profile_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                        if (pageReady) {
                            TextButton(onClick = {
                                captureFailed = false
                                confirmingProfile = true
                                captureRequest++
                            }, enabled = !confirmingProfile) {
                                Text(
                                    if (confirmingProfile) stringResource(R.string.checking)
                                    else stringResource(R.string.use_this_profile),
                                )
                            }
                        }
                    }
                    YtMusicLoginScreen(
                        mode = mode,
                        initialCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) {
                            googleAccounts.firstOrNull { it.accountId == activeAccountId }?.cookie
                        } else null,
                        captureRequest = captureRequest,
                        onPageReady = { pageReady = it },
                        onCaptureUnavailable = {
                            confirmingProfile = false
                            captureFailed = true
                        },
                        onCaptured = { session ->
                            viewModel.onWebSession(session, mode) { accepted ->
                                confirmingProfile = false
                                if (accepted) {
                                    webSession = null
                                    if (mode == WebSessionMode.SIGN_IN) {
                                        navigator.clear()
                                        selectedTab = TAB_LIBRARY
                                    }
                                } else {
                                    captureFailed = true
                                }
                            }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showDiwali,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300)),
        ) {
            val dismissDiwali = {
                showDiwali = false
                AppSettings.setDiwaliGreetingShown()
            }
            BackHandler(onBack = dismissDiwali)
            DiwaliGreetingScreen(onDismiss = dismissDiwali)
        }

        LaunchedEffect(hideRadioTab, hidePodcastsTab) {
            if (hideRadioTab && selectedTab == TAB_RADIO) selectedTab = TAB_HOME
            if (hidePodcastsTab && selectedTab == TAB_PODCASTS) selectedTab = TAB_HOME
        }
        if (showRecognizer) {
            RecognitionSheet(
                hazeState = hazeState,
                onSearch = { query ->
                    navigator.clear()
                    selectedTab = TAB_SEARCH
                    viewModel.onQueryChange(query)
                },
                onDismiss = { showRecognizer = false },
            )
        }
        AppPrompts(
            radioOpen = selectedTab == TAB_RADIO,
            podcastsOpen = selectedTab == TAB_PODCASTS,
            held = !onboardingComplete || showDiwali || showUpdateDialog,
            hazeState = hazeState,
        )

        if (showUpdateDialog && onboardingComplete && !showDiwali) {
            updateNotice?.let { update ->
                UpdateAvailableDialog(
                    version = update.version,
                    notes = update.notes,
                    hazeState = hazeState,
                    onDismiss = { showUpdateDialog = false },
                    onDownload = {
                        if (update.apkUrl != null) {
                            scope.launch { AppUpdateChecker.downloadApk(context) }
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                            showUpdateDialog = false
                        }
                    },
                    onCancelDownload = {
                        AppUpdateChecker.cancelDownload()
                    },
                    onInstall = {
                        val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                        ready?.let { AppUpdateChecker.installApk(context, it.file) }
                    },
                    onOpenReleasePage = {
                        AppUpdateChecker.resetDownload()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        showUpdateDialog = false
                    },
                )
            }
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (songSortMenuOpen) {
            BackHandler { songSortMenuOpen = false }
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                onFlipDateDirection = {
                    detail?.browseId?.let { browseId ->
                        val next = when (songSort) {
                            SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                            SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                            else -> SongSort.DATE_ADDED_DESC
                        }
                        AppSettings.setDetailSongSort(browseId, next)
                    }
                },
                onDismiss = { songSortMenuOpen = false },
            )
        }

        val menuFolder = detail?.browseId?.takeIf { it.isDeviceFolder() }
        if (folderMenuOpen && menuFolder != null) {
            BackHandler { folderMenuOpen = false }
            val downloadsFolder = menuFolder == "local:downloads"
            val viewType = folderViewTypeOf(menuFolder)
            FrostedFolderMenu(
                hazeState = hazeState,
                sort = folderSortOf(menuFolder),
                viewType = viewType,
                onSort = { option ->
                    if (downloadsFolder) AppSettings.setDownloadedMusicSort(option)
                    else AppSettings.setLocalMusicSort(option)
                    folderMenuOpen = false
                },
                onToggleViewType = {
                    val next = if (viewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                    if (downloadsFolder) AppSettings.setDownloadedMusicViewType(next)
                    else AppSettings.setLocalMusicViewType(next)
                    folderMenuOpen = false
                },
                onDismiss = { folderMenuOpen = false },
            )
        }

        if (showAccountSelector) {
            BackHandler { showAccountSelector = false }
            AccountProfileSelector(
                accounts = googleAccounts,
                activeAccountId = activeAccountId,
                activeProfileId = activeProfileId,
                hazeState = hazeState,
                onSelect = { selected, profile -> viewModel.selectProfile(selected.accountId, profile.profileId) },
                onAddAccount = {
                    showAccountSelector = false
                    webSession = WebSessionMode.SIGN_IN
                },
                onRemoveAccount = { selected ->
                    viewModel.removeAccount(selected.accountId)
                    showAccountSelector = false
                },
                onOpenSettings = {
                    showAccountSelector = false
                    navigator.push(AppRoute.Settings)
                },
                onDismiss = { showAccountSelector = false },
            )
        }

        if (showAppLanguage) {
            BackHandler { showAppLanguage = false }
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
            )
        }

        if (showTranslationLanguage) {
            BackHandler { showTranslationLanguage = false }
            TranslationLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showTranslationLanguage = false },
            )
        }

        ScrobbleLoginDialogs(
            hazeState = hazeState,
            showListenBrainzLogin = showListenBrainzLogin,
            listenBrainzToken = listenBrainzToken,
            onListenBrainzSave = { token ->
                AppSettings.setListenBrainzToken(token)
                showListenBrainzLogin = false
            },
            onListenBrainzDismiss = { showListenBrainzLogin = false },
            showLastfmLogin = showLastfmLogin,
            onLastfmSession = { key, username ->
                AppSettings.setLastfmSessionKey(key)
                AppSettings.setLastfmUsername(username)
                AppSettings.setLastfmEnabled(true)
                showLastfmLogin = false
            },
            onLastfmDismiss = { showLastfmLogin = false },
            scope = scope,
            context = context,
        )

        RemoteStorageSettingsDialogs(
            hazeState = hazeState,
            showWebDavEditor = showWebDavEditor,
            onWebDavEditorDismiss = { showWebDavEditor = false },
            showSmbEditor = showSmbEditor,
            onSmbEditorDismiss = { showSmbEditor = false },
        )

        ProviderLoginScreens(
            showDiscordLogin = showDiscordLogin,
            onDiscordLoginDismiss = { showDiscordLogin = false },
            onDiscordTokenCaptured = { token ->
                AppSettings.setDiscordToken(token)
                showDiscordLogin = false
            },
            showSpotifyLogin = showSpotifyLogin,
            onSpotifyLoginDismiss = { showSpotifyLogin = false },
            onSpotifyConnected = { token ->
                AppSettings.setSpotifySpdcToken(token)
                showSpotifyLogin = false
            },
            showSpotifyCanvasAuth = showSpotifyCanvasAuth,
            onSpotifyCanvasAuthDismiss = { showSpotifyCanvasAuth = false },
        )

        discordDialog?.let { which ->
            DiscordDialogHost(
                which = which,
                hazeState = hazeState,
                onDismiss = { discordDialog = null },
            )
        }

        if (editingPartyServer) {
            PartyServerEditor(
                hazeState = hazeState,
                onDismiss = { editingPartyServer = false },
            )
        }

        if (editingSignatureApi) {
            SignatureApiEditor(
                hazeState = hazeState,
                onDismiss = { editingSignatureApi = false },
            )
        }

        SourceConfigurationDialogs(
            hazeState = hazeState,
            editingSource = editingSource,
            onEditSourceDismiss = { editingSource = null },
            scope = scope,
            choosingSourceQuality = choosingSourceQuality,
            onSourceQualityDismiss = { choosingSourceQuality = null },
            explainingOutdatedAddon = explainingOutdatedAddon,
            onOutdatedAddonDismiss = { explainingOutdatedAddon = null },
            confirmJioSaavn = confirmJioSaavn,
            onJioSaavnDismiss = { confirmJioSaavn = false },
        )

        confirmBitPerfect?.let { mode ->
            ConfirmationAlert(
                hazeState = hazeState,
                title = stringResource(R.string.bit_perfect_confirm_title),
                description = stringResource(R.string.bit_perfect_confirm_message),
                confirmLabel = stringResource(R.string.enable),
                onConfirm = {
                    AppSettings.setBitPerfectMode(mode)
                    confirmBitPerfect = null
                },
                onDismiss = { confirmBitPerfect = null },
            )
        }

        if (confirmFixedVolume) {
            ConfirmationAlert(
                hazeState = hazeState,
                title = stringResource(R.string.usb_allow_fixed_volume_confirm_title),
                description = stringResource(R.string.usb_allow_fixed_volume_confirm_message),
                confirmLabel = stringResource(R.string.enable),
                onConfirm = {
                    AppSettings.setUsbAllowFixedVolume(true)
                    confirmFixedVolume = false
                },
                onDismiss = { confirmFixedVolume = false },
            )
        }

        DacVolumeHud(hazeState)

        AddonPlaybackAlerts(
            hazeState = hazeState,
            blockedAddonSong = blockedAddonSong,
            onBlockedAddonDismiss = { blockedAddonSong = null },
            addonLeftOutOfDownload = addonLeftOutOfDownload,
            inOwnWindow = playerRaised || songActions != null || browseActions != null,
            onAddonLeftOutDismiss = { addonLeftOutOfDownload = null },
        )

    }
}

private val explainedDownloadExclusions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

private val OnboardingDismissEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

internal fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

private fun String?.isDeviceFolder(): Boolean =
    this != null && startsWith("local:") &&
        !startsWith(Downloads.PLAYLIST_PREFIX) && !startsWith(LocalGroups.PREFIX) &&
        !startsWith(OfflinePlaylists.PREFIX)

private fun playingYouTubesOwn(videoId: String, controller: MediaController?): Boolean {
    val item = controller?.currentMediaItem?.takeIf { it.mediaId == videoId } ?: return false
    val uri = item.localConfiguration?.uri
    return QualityUpgrade.isKnownToBePlayingYouTubesOwn(
        videoId,
        uri?.let(QualityUpgrade::cacheTag),
        uri?.let(AudioCache::originOf),
    )
}

private fun formatDurationText(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

private const val SEEK_END_GUARD_MS = 1_000L

private val DETAIL_TITLE_DROP = 320.dp

private val PLAYER_PULL_FLING_VELOCITY = 400.dp

private const val PLAYER_SCRIM_ALPHA = 0.32f

@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

private const val SNAPSHOT_RELEASE_DELAY_MS = 400L

private object PlayerSheetShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, -size.height, size.width, size.height))
}

private const val TAB_HOME = 0
private const val TAB_LIBRARY = 1
private const val TAB_RADIO = 2
private const val TAB_EXPLORE = 3
private const val TAB_SEARCH = 4
private const val TAB_PODCASTS = 5

private var tabBeforeSearch by mutableIntStateOf(TAB_HOME)

@Composable
private fun classicSearchOn(): Boolean = AppSettings.classicSearch.collectAsStateWithLifecycle().value

@Composable
private fun searchCategories(viewModel: MainViewModel): List<MoodGenre> {
    val classic by AppSettings.classicSearch.collectAsStateWithLifecycle()
    val explore by viewModel.explore.collectAsStateWithLifecycle()
    LaunchedEffect(classic) { if (!classic) viewModel.onExploreShown() }
    if (classic) return emptyList()
    return (explore as? UiState.Success)?.data?.flatMap { it.items }?.distinctBy { it.browseId + it.params }.orEmpty()
}
