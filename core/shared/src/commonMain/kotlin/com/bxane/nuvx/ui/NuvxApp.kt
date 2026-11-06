package com.bxane.nuvx.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection

private val Tabs = listOf(
    Tab("Home", NuvxIcons.Home, "house.fill"),
    Tab("Explore", NuvxIcons.Explore, "square.grid.2x2.fill"),
    Tab("Library", NuvxIcons.Library, "music.note.square.stack.fill"),
)
internal enum class Page(val title: String) { TOGETHER("Listen Together"), SETTINGS("Settings"), QUEUE("Queue"), HISTORY("History"), LIKED("Liked songs"), LOCAL_LIBRARY("On this device") }

internal fun compactNavigationTab(tab: Int, searching: Boolean, page: Page?, albumTitle: String?, artistTitle: String?): Tab = when {
    searching -> Tab("Search", NuvxIcons.Search, "magnifyingglass")
    page != null -> when (page) {
        Page.TOGETHER -> Tab(page.title, NuvxIcons.Together, "person.2.fill")
        Page.SETTINGS -> Tab(page.title, NuvxIcons.Settings, "gearshape.fill")
        Page.QUEUE -> Tab(page.title, NuvxIcons.Queue, "list.bullet")
        Page.HISTORY -> Tab(page.title, NuvxIcons.History, "clock.arrow.circlepath")
        Page.LIKED -> Tab(page.title, NuvxIcons.HeartFilled, "heart.fill")
        Page.LOCAL_LIBRARY -> Tab(page.title, NuvxIcons.Library, "iphone")
    }
    albumTitle != null || artistTitle != null -> Tab(albumTitle ?: artistTitle.orEmpty(), NuvxIcons.Library, "square.stack")
    else -> Tabs[tab]
}

@Composable
fun NuvxApp(
    router: BackendRouter, store: KeyValueStore, player: NuvxPlayer, lyrics: LyricsChain, watch: Boolean, reduceMotion: Boolean = false, tv: Boolean = false,
    androidUi: Boolean = false,
    pictureInPicture: Boolean = false,
    localLibrary: LocalMusicLibrary? = null,
    /** Platform back integration (Android passes BackHandler). Called every composition with whether back has somewhere to go. */
    onBackHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
    onSystemBarIconsDark: (Boolean) -> Unit = {},
    /** Platform video for community canvases: a muted, looping, cropped video filling [Modifier]. Null hides canvases. */
    canvasVideo: (@Composable (url: String, modifier: Modifier) -> Unit)? = null,
    /** The active session's video surface, attached only while the expanded player is visible. */
    videoSurface: (@Composable (modifier: Modifier) -> Unit)? = null,
    desktop: Boolean = false,
    desktopWindowSettings: DesktopWindowSettings? = null,
    onDesktopWindowSettingsChange: (DesktopWindowSettings) -> Unit = {},
    openSystemEqualizer: (() -> Unit)? = null,
    desktopAudioOutputOptions: List<DesktopAudioOutputOption> = emptyList(),
    onSetDesktopAudioOutput: (DesktopAudioOutputSelection) -> Unit = {},
    onSetDesktopCrossfadeSeconds: (Int) -> Unit = {},
) {
    val state by player.state.collectAsState()
    var welcomed by remember { mutableStateOf(store.get("welcomeSeen") == "true") }
    // Full-bleed cover art in the player is the default; Settings can switch back to the square cover.
    var fullBleed by remember { mutableStateOf(store.get("fullBleedArt") != "false") }
    var magneticNavigation by remember { mutableStateOf(store.get("magneticNavigation") == "true") }
    var darkMode by remember(store) { mutableStateOf(ThemeModePreference.read(store)) }
    var reduceMotionPreference by remember(store, watch) { mutableStateOf(ReducedMotionPreference.read(store, watch)) }
    val reduceMotionEnabled = reduceMotion || reduceMotionPreference
    val updateMagneticNavigation: (Boolean) -> Unit = { enabled ->
        magneticNavigation = enabled
        store.put("magneticNavigation", enabled.toString())
    }
    val updateDarkMode: (Boolean) -> Unit = { enabled ->
        darkMode = ThemeModePreference.write(store, enabled)
    }
    val updateReduceMotion: (Boolean) -> Unit = { enabled ->
        reduceMotionPreference = ReducedMotionPreference.write(store, enabled)
    }
    val lyricProviders = remember(lyrics) { lyrics.providerNames }
    var lyricSettings by remember(store, lyricProviders) {
        mutableStateOf(LyricsProviderSettings.load(store, lyricProviders))
    }
    var lyricsTextSize by remember(store) { mutableStateOf(LyricsTextSizePreference.read(store)) }
    var communityCanvasEnabled by remember(store) { mutableStateOf(store.get("communityCanvas") != "false") }
    var accentHex by remember(store) { mutableStateOf(AccentPalettePreference.read(store)) }
    var accentSwatches by remember(store) { mutableStateOf(AccentPalettePreference.readSwatches(store)) }
    var playbackSpeed by remember(store) { mutableStateOf(PlaybackSpeed.read(store)) }
    var desktopPlaybackSettings by remember(store) { mutableStateOf(DesktopPlaybackSettings.load(store)) }
    var radioSettings by remember(store) { mutableStateOf(RadioSettings.load(store)) }
    var lyricsOffsetMs by remember(store, state.current?.playbackVideoId) {
        mutableLongStateOf(LyricsTimingOffset.read(store, state.current))
    }
    val updateLyrics: (LyricsProviderSettings) -> Unit = { value ->
        value.normalized(lyricProviders).also { it.save(store); lyricSettings = it }
    }
    val updateLyricsTextSize: (LyricsTextSizePreference) -> Unit = { size -> lyricsTextSize = LyricsTextSizePreference.write(store, size) }
    val updatePlaybackSpeed: (Float) -> Unit = { value -> playbackSpeed = PlaybackSpeed.write(store, value) }
    val updateDesktopPlaybackSettings: (DesktopPlaybackSettings) -> Unit = { value -> value.also { it.save(store); desktopPlaybackSettings = it } }
    val updateRadioSettings: (RadioSettings) -> Unit = { value -> value.also { it.save(store); radioSettings = it } }
    val updateAccentPalette: (AccentPaletteDraft) -> Unit = { draft ->
        accentHex = draft.apply(store)
        accentSwatches = AccentPalettePreference.readSwatches(store)
    }
    val updateDesktopAudioOutput: (DesktopAudioOutputSelection) -> Unit = { selection ->
        updateDesktopPlaybackSettings(desktopPlaybackSettings.copy(audioOutput = selection))
    }
    val updateDesktopWindowSettings: (DesktopWindowSettings) -> Unit = { value ->
        value.normalized().also { it.save(store); onDesktopWindowSettingsChange(it) }
    }
    val updateLyricsOffset: (Long) -> Unit = { value -> lyricsOffsetMs = LyricsTimingOffset.write(store, state.current, value) }
    val usableDesktopAudioOutput = desktopPlaybackSettings.audioOutput.let { selection ->
        if (desktop && !selection.isSystemDefault && desktopAudioOutputOptions.none { it.selection == selection }) {
            DesktopAudioOutputSelection.DEFAULT
        } else selection
    }
    LaunchedEffect(player, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(desktop, usableDesktopAudioOutput) {
        if (desktop) {
            if (usableDesktopAudioOutput != desktopPlaybackSettings.audioOutput) {
                updateDesktopPlaybackSettings(desktopPlaybackSettings.copy(audioOutput = usableDesktopAudioOutput))
            }
            onSetDesktopAudioOutput(usableDesktopAudioOutput)
        }
    }
    LaunchedEffect(desktop, desktopPlaybackSettings.crossfadeSeconds) {
        if (desktop) onSetDesktopCrossfadeSeconds(desktopPlaybackSettings.crossfadeSeconds)
    }
    // shortcut: the room and history live as long as this composition (an Android activity recreation restarts them); move to the app graph when that matters.
    val scope = rememberCoroutineScope()
    val together = remember(player) { ListenTogether(player, scope) }
    val sleep = remember(player) { SleepTimer(player, scope) }
    val radio = remember(router, player, scope) { RadioStation(router, player, scope, avoidRepeats = radioSettings.avoidRepeats) }
    LaunchedEffect(radioSettings.avoidRepeats) { radio.avoidRepeats = radioSettings.avoidRepeats }
    LaunchedEffect(radio, state.queue, state.index) { radio.onState(state) }
    DisposableEffect(radio) { onDispose { radio.stop() } }
    val sleepMode by sleep.mode.collectAsState()
    val canvases = remember(canvasVideo != null, communityCanvasEnabled) {
        if (canvasVideo != null && communityCanvasEnabled) CommunityCanvas() else null
    }
    val history = remember(store) { History(store) }
    val recentlyPlayed by history.played.collectAsState()
    LaunchedEffect(state.current?.videoId) { state.current?.let(history::played) }

    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var settingsQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<Page?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<Collection?>(null) }
    var artist by remember { mutableStateOf<Collection?>(null) }
    // Scrolling down folds the tab bar to the current destination icon beside the mini-player; scrolling up restores it.
    var collapsed by remember { mutableStateOf(false) }
    val scrollWatch = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -4f) collapsed = true else if (available.y > 4f) collapsed = false
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(tab, searching, page, album, artist) { collapsed = false }
    // Albums and playlists open on top of an artist page; another artist replaces it.
    val open = { c: Collection -> if (c.kind == Collection.Kind.ARTIST) { artist = c; album = null } else album = c }
    // Back unwinds one layer at a time: player sheet, sub-page, search, then a non-Home tab returns to Home.
    val canGoBack = welcomed && !watch && !tv && (sheet || album != null || artist != null || page != null || searching || tab != 0)
    if (!tv) onBackHandler?.invoke(canGoBack) {
        when {
            sheet -> sheet = false
            album != null -> album = null
            artist != null -> artist = null
            page != null -> page = null
            searching -> searching = false
            else -> tab = 0
        }
    }

    val palette = if (androidUi) {
        if (darkMode) NuvxPalette.Dark else NuvxPalette.Light
    } else NuvxPalette.Dark
    CompositionLocalProvider(
        LocalDesktopLayout provides desktop,
        LocalGlassMotionReduced provides reduceMotionEnabled,
        LocalUserAccent provides AccentPalettePreference.color(accentHex),
        LocalNuvxPalette provides palette,
    ) {
    SideEffect { onSystemBarIconsDark(!palette.dark && !sheet) }
    val colorScheme = if (palette.dark) {
        darkColorScheme(primary = NuvxColors.Accent, onPrimary = NuvxColors.OnAccent, background = NuvxColors.Background,
            surface = NuvxColors.Card, onSurface = NuvxColors.Text, surfaceVariant = NuvxColors.Card,
            onSurfaceVariant = NuvxColors.Secondary, outline = NuvxColors.Divider)
    } else {
        lightColorScheme(primary = NuvxColors.Accent, onPrimary = NuvxColors.OnAccent, background = NuvxColors.Background,
            surface = NuvxColors.Card, onSurface = NuvxColors.Text, surfaceVariant = Color(0xFFF0F0F3),
            onSurfaceVariant = NuvxColors.Secondary, outline = NuvxColors.Divider)
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize(), color = NuvxColors.Background) {
            when {
                pictureInPicture && videoSurface != null -> Box(Modifier.fillMaxSize().background(Color.Black)) { videoSurface(Modifier.fillMaxSize()) }
                watch -> WatchApp(router, store, player, reduceMotionEnabled, reduceMotionPreference, updateReduceMotion)
                !welcomed -> {
                    val finishWelcome = { store.put("welcomeSeen", "true"); welcomed = true }
                    if (androidUi) AndroidWelcome(finishWelcome, reduceMotionEnabled) else Welcome(finishWelcome)
                }
                tv -> TvShell(router, history, state, player, lyrics, localLibrary, reduceMotionEnabled, magneticNavigation, onBackHandler, sleep, radio,
                    lyricSettings, updateLyrics, lyricsOffsetMs, updateLyricsOffset, lyricsTextSize = lyricsTextSize,
                    canvas = canvases?.let { c -> canvasVideo?.let { v -> c to v } }) { tvPage ->
                    when (tvPage) {
                        TvSubpage.TOGETHER -> TogetherPage(together, store)
                        TvSubpage.SETTINGS -> SettingsPage(router, store, lyricProviders, lyricSettings, updateLyrics,
                            darkMode = darkMode, onDarkModeChange = updateDarkMode,
                            lyricsTextSize = lyricsTextSize, onLyricsTextSizeChange = updateLyricsTextSize,
                            radioSettings = radioSettings, onRadioSettingsChange = updateRadioSettings,
                            playbackSpeed = playbackSpeed, onPlaybackSpeed = updatePlaybackSpeed,
                            player = player, playerState = state, sleepTimer = sleep, sleepMode = sleepMode,
                            onOpenSystemEqualizer = openSystemEqualizer,
                            fullBleed = fullBleed, onFullBleed = { fullBleed = !fullBleed; store.put("fullBleedArt", fullBleed.toString()) },
                            reduceMotionPreference = reduceMotionPreference, onReduceMotionChange = updateReduceMotion, reduceMotion = reduceMotionEnabled,
                            magneticNavigation = magneticNavigation, onMagneticNavigation = updateMagneticNavigation,
                            canvasEnabled = communityCanvasEnabled.takeIf { canvasVideo != null },
                            onCanvasEnabled = { communityCanvasEnabled = it; store.put("communityCanvas", it.toString()) },
                            accentHex = accentHex, accentSwatches = accentSwatches,
                            onAccentPaletteChange = updateAccentPalette)
                        TvSubpage.QUEUE -> QueuePage(state, player, dpadReorder = true)
                        TvSubpage.LIKED -> LikedPage(history, state, player)
                        TvSubpage.LOCAL_LIBRARY -> if (localLibrary != null) LocalLibraryScreen(localLibrary, player) else Unit
                    }
                }
                else -> SharedTransitionLayout { CompositionLocalProvider(LocalArtMorph provides (if (reduceMotionEnabled) null else ArtMorph(this, sheet && state.current != null))) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    val select = { i: Int -> tab = i; searching = false; page = null; album = null; artist = null }
                    val openSearch = { q: String? -> searchQuery = q; searching = true; page = null; album = null; artist = null }
                    val openPage = { p: Page -> page = p }
                    val routeContent: @Composable () -> Unit = {
                        when {
                            album != null -> AlbumShell({ album = null }) { AlbumPage(router, player, state, album!!) }
                            artist != null -> AlbumShell({ artist = null }) { ArtistPage(router, player, state, artist!!, open, reduceMotionEnabled) }
                            page != null -> SubPage(page!!, { page = null }) {
                                when (page!!) {
                                    Page.TOGETHER -> TogetherPage(together, store)
                                    Page.SETTINGS -> SettingsPage(router, store, lyricProviders, lyricSettings, updateLyrics,
                                        { openPage(Page.TOGETHER) }, { openPage(Page.LIKED) },
                                        if (localLibrary != null) ({ openPage(Page.LOCAL_LIBRARY) }) else null,
                                        darkMode = darkMode.takeIf { androidUi }, onDarkModeChange = updateDarkMode,
                                        lyricsTextSize = lyricsTextSize, onLyricsTextSizeChange = updateLyricsTextSize,
                                        radioSettings = radioSettings, onRadioSettingsChange = updateRadioSettings,
                                        playbackSpeed = playbackSpeed, onPlaybackSpeed = updatePlaybackSpeed,
                                         player = player, playerState = state, sleepTimer = sleep, sleepMode = sleepMode,
                                        onOpenSystemEqualizer = openSystemEqualizer,
                                        fullBleed = fullBleed, onFullBleed = { fullBleed = !fullBleed; store.put("fullBleedArt", fullBleed.toString()) },
                                        reduceMotionPreference = reduceMotionPreference, onReduceMotionChange = updateReduceMotion, reduceMotion = reduceMotionEnabled,
                                        magneticNavigation = magneticNavigation, onMagneticNavigation = updateMagneticNavigation,
                                        canvasEnabled = communityCanvasEnabled.takeIf { canvasVideo != null },
                                        onCanvasEnabled = { communityCanvasEnabled = it; store.put("communityCanvas", it.toString()) },
                                        hideDesktopVolumeBar = desktopPlaybackSettings.hideVolumeBar.takeIf { desktop },
                                        onHideDesktopVolumeBar = { updateDesktopPlaybackSettings(desktopPlaybackSettings.copy(hideVolumeBar = it)) },
                                        desktopAudioOutputOptions = desktopAudioOutputOptions.takeIf { desktop }.orEmpty(),
                                        desktopAudioOutput = desktopPlaybackSettings.audioOutput.takeIf { desktop },
                                        onDesktopAudioOutputChange = updateDesktopAudioOutput,
                                        desktopCrossfadeSeconds = desktopPlaybackSettings.crossfadeSeconds.takeIf { desktop },
                                        onDesktopCrossfadeChange = { updateDesktopPlaybackSettings(desktopPlaybackSettings.copy(crossfadeSeconds = it)) },
                                        desktopWindowSettings = desktopWindowSettings.takeIf { desktop },
                                        onDesktopWindowSettingsChange = updateDesktopWindowSettings,
                                        searchQuery = settingsQuery.takeIf { desktop },
                                        onSearchQueryChange = { settingsQuery = it },
                                        accentHex = accentHex, accentSwatches = accentSwatches,
                                         onAccentPaletteChange = updateAccentPalette, androidUi = androidUi, onBackHandler = onBackHandler.takeIf { !sheet })
                                    Page.QUEUE -> QueuePage(state, player)
                                    Page.HISTORY -> PlaybackHistoryPage(history, state, player)
                                    Page.LIKED -> LikedPage(history, state, player)
                                    Page.LOCAL_LIBRARY -> if (localLibrary != null) LocalLibraryScreen(localLibrary, player) else Unit
                                }
                            }
                            searching -> SearchScreen(
                                router, player, history, searchQuery, open,
                                onAccount = { openPage(Page.SETTINGS) },
                                onSubmittedQuery = { searchQuery = it },
                                androidUi = androidUi,
                            )
                            tab == 0 -> HomeScreen(
                                history, router, player,
                                onTogether = { openPage(Page.TOGETHER) }, onAccount = { openPage(Page.SETTINGS) },
                                onExplore = { select(1) }, onSearch = { openSearch(null) }, onMood = { openSearch(it) },
                                androidUi = androidUi,
                            )
                            tab == 1 -> ExploreScreen(
                                onMood = { openSearch(it) }, router = router,
                                onAccount = { openPage(Page.SETTINGS) }, androidUi = androidUi,
                                recommendationSeed = recentlyPlayed.firstOrNull { !it.isLocal },
                                onPlayRecommendations = { tracks, index -> player.playQueue(tracks, index) },
                            )
                            else -> LibraryScreen(
                                history, state, player,
                                onLiked = { openPage(Page.LIKED) }, onAccount = { openPage(Page.SETTINGS) },
                                onLocalLibrary = if (localLibrary != null) ({ openPage(Page.LOCAL_LIBRARY) }) else null,
                                motionEnabled = !reduceMotionEnabled, androidUi = androidUi,
                            )
                        }
                    }
                    if (desktop) {
                        val title = when {
                            album != null -> album!!.title
                            artist != null -> artist!!.title
                            page != null -> page!!.title
                            searching -> "Search"
                            tab == 0 -> "Home"
                            tab == 1 -> "Explore"
                            else -> "Your library"
                        }
                        DesktopAppFrame(
                            title = title, selectedTab = tab, searchSelected = searching,
                            selectedPage = page?.title?.takeUnless { page == Page.SETTINGS } ?: album?.title ?: artist?.title,
                            searchQuery = searchQuery,
                            settingsQuery = settingsQuery.takeIf { page == Page.SETTINGS },
                            onSettingsQueryChange = { settingsQuery = it },
                            player = player, state = state, lyrics = lyrics, lyricSettings = lyricSettings, lyricsTextSize = lyricsTextSize, reduceMotion = reduceMotionEnabled,
                            magneticNavigation = magneticNavigation,
                            onLyricsSettingsChange = updateLyrics, lyricsOffsetMs = lyricsOffsetMs, onLyricsOffsetChange = updateLyricsOffset,
                            onSelectHome = { select(0) }, onSelectSearch = { openSearch(null) }, onSubmitSearch = { openSearch(it) }, onSelectExplore = { select(1) },
                            onSelectLibrary = { select(2) },
                            onSelectHistory = { openPage(Page.HISTORY) },
                            onSelectLocal = if (localLibrary != null) ({ openPage(Page.LOCAL_LIBRARY) }) else null,
                            onSelectTogether = { openPage(Page.TOGETHER) }, onSelectSettings = { openPage(Page.SETTINGS) },
                            onOpenPlayer = { sheet = true }, hideVolumeBar = desktopPlaybackSettings.hideVolumeBar, content = routeContent,
                        )
                    } else Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().safeDrawingPadding()) {
                            val content = Modifier.align(Alignment.TopCenter).fillMaxSize().then(if (wide) Modifier.widthIn(max = 1100.dp) else Modifier)
                            NuvxGlassCaptureContent { Box(content.nestedScroll(scrollWatch)) { routeContent() } }
                            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.animation.AnimatedContent(collapsed,
                                    transitionSpec = {
                                        val transition = if (reduceMotionEnabled) {
                                            androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                                        } else {
                                            val direction = if (targetState) 1 else -1
                                            (slideInVertically(spring(dampingRatio = 0.82f, stiffness = 520f)) { it * direction / 4 } + fadeIn(tween(160))) togetherWith
                                                (slideOutVertically(spring(dampingRatio = 0.86f, stiffness = 560f)) { -it * direction / 4 } + fadeOut(tween(120)))
                                        }
                                        transition.using(SizeTransform { _, _ ->
                                            if (reduceMotionEnabled) snap() else spring(dampingRatio = 0.86f, stiffness = 560f)
                                        })
                                    }, label = "chrome") { compact ->
                                    if (compact) Row(
                                        if (state.current != null) Modifier.fillMaxWidth() else Modifier,
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        val current = compactNavigationTab(tab, searching, page, album?.title, artist?.title)
                                        GlassIconButton(current.icon, "Show navigation, ${current.label} selected", size = if (androidUi) 64.dp else 56.dp,
                                            onClick = { collapsed = false }, symbolName = current.symbolName, selected = true, motionEnabled = !reduceMotionEnabled, liquid = true)
                                        if (state.current != null) MiniPlayerPill(state, player::togglePlay, player::next, { sheet = true }, Modifier.weight(1f), onPrevious = player::previous, desktop = desktop, androidUi = androidUi, reduceMotion = reduceMotionEnabled)
                                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MiniPlayerPill(state, player::togglePlay, player::next, { sheet = true }, onPrevious = player::previous, desktop = desktop, androidUi = androidUi, reduceMotion = reduceMotionEnabled)
                                        TabBar(Tabs, tab, searching, select, onSearch = { openSearch(null) }, androidUi = androidUi,
                                            reduceMotion = reduceMotionEnabled, magneticEnabled = magneticNavigation)
                                    }
                                }
                            }
                        }
                    }
                    // Springs rather than fixed-time slides, so opening and closing feel like a physical sheet.
                    AnimatedVisibility(sheet && state.current != null,
                        enter = if (reduceMotionEnabled) fadeIn() else slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it },
                        exit = if (reduceMotionEnabled) fadeOut() else slideOutVertically(spring(stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it }) {
                        NowPlayingSheet(player, state, lyrics, history, router, reduceMotionEnabled, onOpen = { sheet = false; open(it) }, sleep = sleep, fullBleed = fullBleed,
                            lyricSettings = lyricSettings, onLyricSettingsChange = updateLyrics, artScope = this,
                            lyricsOffsetMs = lyricsOffsetMs, onLyricsOffsetChange = updateLyricsOffset, lyricsTextSize = lyricsTextSize,
                            androidUi = androidUi,
                            canvas = canvases?.let { c -> canvasVideo?.let { v -> c to v } }, videoSurface = videoSurface, radio = radio) { sheet = false }
                    }
                }
                } }
            }
        }
    }
    }
}

@Composable private fun SubPage(page: Page, onBack: () -> Unit, body: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) {
    if (!LocalDesktopLayout.current) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton("Back", onBack, size = 44.dp) {
                Icon(NuvxIcons.Chevron, null, Modifier.size(20.dp).graphicsLayerMirror(), tint = NuvxColors.Text)
            }
        }
        LargeTitle(page.title, Modifier.padding(bottom = 8.dp))
    }
    Box(Modifier.weight(1f)) { body() }
}

private fun Modifier.graphicsLayerMirror() = this.then(Modifier.scale(-1f, 1f))

@Composable private fun Welcome(onDone: () -> Unit) = Column(Modifier.safeDrawingPadding().fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
    NuvxBrandMark("nuvx — bxane's mark", Modifier.size(96.dp), contentScale = ContentScale.Fit)
    Spacer(Modifier.height(28.dp))
    Text("Your next favourite.", color = NuvxColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    Text("Music, with room to listen.", color = NuvxColors.Accent, fontSize = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
    Text("Search YouTube Music and play available songs as a guest. No Google account is connected. Availability depends on the service and your region.", color = NuvxColors.Secondary, fontSize = 16.sp)
    Box(Modifier.padding(top = 32.dp).clip(CircleShape).background(Color.White).clickable(onClick = onDone).padding(horizontal = 28.dp, vertical = 14.dp)
        .semantics { role = Role.Button }) {
        Text("Start listening", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable private fun QueuePage(state: PlayerState, player: NuvxPlayer, dpadReorder: Boolean = false) {
    val listState = rememberLazyListState()
    val upFocus = remember(dpadReorder, state.queue.size) { if (dpadReorder) List(state.queue.size) { FocusRequester() } else emptyList() }
    val downFocus = remember(dpadReorder, state.queue.size) { if (dpadReorder) List(state.queue.size) { FocusRequester() } else emptyList() }
    var focusAfterMove by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    LaunchedEffect(state.queue, focusAfterMove) {
        val (index, up) = focusAfterMove ?: return@LaunchedEffect
        if (!dpadReorder) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        withFrameNanos { }
        val focusUp = if (up) index > 0 else index >= state.queue.lastIndex
        (if (focusUp) upFocus else downFocus).getOrNull(index)?.requestFocus()
        focusAfterMove = null
    }

    if (state.queue.isEmpty()) {
        Text("Your queue is empty. Play something from Search or Home.", color = NuvxColors.Secondary, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
    } else {
        androidx.compose.foundation.lazy.LazyColumn(state = listState, contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
            items(state.queue.size) { i ->
                val track = state.queue[i]
                TrackRow(
                    track,
                    Modifier.reorderable(i, state.queue.lastIndex, player::move),
                    active = i == state.index,
                    trailing = if (dpadReorder || i != state.index) ({
                        if (dpadReorder) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            QueueMoveButton(track.title, up = true, enabled = i > 0, focusRequester = upFocus[i]) {
                                player.move(i, i - 1)
                                focusAfterMove = (i - 1) to true
                            }
                            QueueMoveButton(track.title, up = false, enabled = i < state.queue.lastIndex, focusRequester = downFocus[i]) {
                                player.move(i, i + 1)
                                focusAfterMove = (i + 1) to false
                            }
                        }
                        if (i != state.index) PlainIconButton(NuvxIcons.Close, "Remove " + track.title + " from the queue", 48.dp) { player.remove(i) }
                    }) else null,
                ) { player.skipTo(i) }
            }
        }
    }
}

@Composable
private fun QueueMoveButton(trackTitle: String, up: Boolean, enabled: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusRequester(focusRequester).size(48.dp).focusRing().semantics {
            contentDescription = "Move " + trackTitle + (if (up) " up" else " down")
        },
    ) {
        Icon(
            NuvxIcons.Chevron,
            null,
            Modifier.size(20.dp).rotate(if (up) -90f else 90f),
            tint = if (enabled) NuvxColors.Text else Color.White.copy(alpha = 0.3f),
        )
    }
}
/** One settings row: icon, label, optional subtitle, and a trailing value, switch or chevron. */
internal class SettingRow(
    val icon: androidx.compose.ui.graphics.vector.ImageVector?, val label: String, val subtitle: String? = null,
    val value: String? = null, val toggle: Boolean? = null, val onClick: (() -> Unit)? = null,
    val choices: List<String>? = null, val onChoose: ((String) -> Unit)? = null, val segmented: Boolean = false,
)

private data class AndroidSettingsCategory(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val sections: Set<String?>,
)

private val AndroidSettingsCategories = listOf(
    AndroidSettingsCategory("Playback", "Queue, streaming and sound", NuvxIcons.Play, setOf(null, "PLAYBACK", "STREAMING", "AUDIO QUALITY", "VIDEO QUALITY")),
    AndroidSettingsCategory("Appearance", "Theme, colour and motion", NuvxIcons.Settings, setOf("APPEARANCE", "NAVIGATION")),
    AndroidSettingsCategory("Lyrics", "Sources, timing and text", NuvxIcons.Lyrics, setOf("LYRICS")),
    AndroidSettingsCategory("Library", "Your saved and local music", NuvxIcons.Library, setOf("LIBRARY")),
    AndroidSettingsCategory("About", "Project and contact links", NuvxIcons.Person, setOf("ABOUT")),
)

/** Grouped settings: searchable rows in rounded cards with small-caps section labels, then the About links and a footer. */
@Composable internal fun SettingsPage(router: BackendRouter, store: KeyValueStore, lyricProviders: List<String>, lyricSettings: LyricsProviderSettings,
    onLyricsChange: (LyricsProviderSettings) -> Unit, onTogether: (() -> Unit)? = null, onLiked: (() -> Unit)? = null, onLocal: (() -> Unit)? = null,
    darkMode: Boolean? = null, onDarkModeChange: (Boolean) -> Unit = {},
    lyricsTextSize: LyricsTextSizePreference = LyricsTextSizePreference.DEFAULT, onLyricsTextSizeChange: (LyricsTextSizePreference) -> Unit = {},
    fullBleed: Boolean? = null, onFullBleed: () -> Unit = {}, canvasEnabled: Boolean? = null, onCanvasEnabled: (Boolean) -> Unit = {},
    reduceMotionPreference: Boolean = false, onReduceMotionChange: (Boolean) -> Unit = {}, reduceMotion: Boolean = false,
    magneticNavigation: Boolean = false, onMagneticNavigation: (Boolean) -> Unit = {},
    radioSettings: RadioSettings = RadioSettings(), onRadioSettingsChange: (RadioSettings) -> Unit = {},
    playbackSpeed: Float = PlaybackSpeed.DEFAULT, onPlaybackSpeed: (Float) -> Unit = {}, onOpenSystemEqualizer: (() -> Unit)? = null,
    hideDesktopVolumeBar: Boolean? = null, onHideDesktopVolumeBar: (Boolean) -> Unit = {},
    desktopAudioOutputOptions: List<DesktopAudioOutputOption> = emptyList(),
    desktopAudioOutput: DesktopAudioOutputSelection? = null,
    onDesktopAudioOutputChange: (DesktopAudioOutputSelection) -> Unit = {},
    desktopCrossfadeSeconds: Int? = null,
    onDesktopCrossfadeChange: (Int) -> Unit = {},
    desktopWindowSettings: DesktopWindowSettings? = null, onDesktopWindowSettingsChange: (DesktopWindowSettings) -> Unit = {},
     searchQuery: String? = null, onSearchQueryChange: ((String) -> Unit)? = null,
     player: NuvxPlayer? = null, playerState: PlayerState? = null,
    sleepTimer: SleepTimer? = null, sleepMode: SleepTimer.Mode? = null,
    accentHex: String = AccentPalettePreference.DEFAULT, accentSwatches: List<String> = AccentPalettePreference.DEFAULT_SWATCHES,
     onAccentPaletteChange: (AccentPaletteDraft) -> Unit = {}, androidUi: Boolean = false,
    onBackHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null) {
    val selected by router.selected.collectAsState()
    val fallback by router.fallback.collectAsState()
    val audioQuality by router.audioQuality.collectAsState()
    val videoQuality by router.videoQuality.collectAsState()
    val health by router.health.collectAsState()
    val uri = LocalUriHandler.current
    var localQuery by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var androidCategory by remember { mutableStateOf<String?>(null) }
    val query = searchQuery ?: localQuery
    val desktopSettings = searchQuery != null
    val changeQuery = onSearchQueryChange ?: { localQuery = it }
    val sleepModes = listOf(15, 30, 45, 60).map { SleepTimer.Mode.After(it) } + SleepTimer.Mode.EndOfTrack
    fun status(b: BackendId) = health[b]?.let { "${if (it.ok) "Working" else "Unavailable"} · ${it.latencyMs} ms" } ?: "Not used yet this session"
    val sections: List<Pair<String?, List<SettingRow>>> = listOf(
        null to listOfNotNull(onTogether?.let { SettingRow(NuvxIcons.Together, "Listen together", "Share a code and play the same music, in time", onClick = it) }),
        "PLAYBACK" to listOfNotNull(
            SettingRow(null, "Avoid repeats in radio session", "Skip tracks already played or queued in this radio session",
                toggle = radioSettings.avoidRepeats,
                onClick = { onRadioSettingsChange(radioSettings.copy(avoidRepeats = !radioSettings.avoidRepeats)) }),
            player?.let { activePlayer -> playerState?.let { current ->
                SettingRow(null, "Repeat", "Repeat the current track or queue", value = when (current.repeat) {
                    RepeatMode.OFF -> "Off"
                    RepeatMode.ALL -> "All"
                    RepeatMode.ONE -> "One"
                }, choices = listOf("Off", "All", "One"), onChoose = { choice ->
                    activePlayer.setRepeat(when (choice) {
                        "All" -> RepeatMode.ALL
                        "One" -> RepeatMode.ONE
                        else -> RepeatMode.OFF
                    })
                })
            } },
            player?.let { activePlayer -> playerState?.takeIf { it.queue.size > it.index + 1 }?.let {
                SettingRow(null, "Shuffle", "Mix the order of upcoming songs", onClick = activePlayer::shuffleUpNext)
            } },
            SettingRow(null, "Playback speed", "Adjust audio speed from 0.5× to 2×", value = PlaybackSpeed.label(playbackSpeed),
                choices = PlaybackSpeed.options.map(PlaybackSpeed::label), onChoose = { choice ->
                    PlaybackSpeed.options.firstOrNull { PlaybackSpeed.label(it) == choice }?.let(onPlaybackSpeed)
                }),
            sleepTimer?.let { timer ->
                val choices = listOf("Off") + sleepModes.mapNotNull { sleepLabel(it)?.replaceFirstChar { char -> char.uppercase() } }
                SettingRow(null, "Sleep timer", "Pause playback after a while",
                    value = sleepMode?.let { sleepLabel(it)?.replaceFirstChar { char -> char.uppercase() } } ?: "Off",
                    choices = choices, onChoose = { choice ->
                        when (choice) {
                            "Off" -> timer.set(null)
                            else -> sleepModes.firstOrNull { sleepLabel(it)?.replaceFirstChar { char -> char.uppercase() } == choice }?.let(timer::set)
                        }
                    })
            },
            desktopAudioOutput?.let { output -> SettingRow(NuvxIcons.SpeakerLow, "Output device", "Changing the device restarts the current track at its position.",
                value = desktopAudioOutputOptions.firstOrNull { it.selection == output }?.label ?: "System default",
                choices = (listOf("System default") + desktopAudioOutputOptions.map { it.label }).takeIf { desktopAudioOutputOptions.isNotEmpty() },
                onChoose = { choice ->
                    val selected = desktopAudioOutputOptions.firstOrNull { it.label == choice }?.selection
                    onDesktopAudioOutputChange(selected ?: DesktopAudioOutputSelection.DEFAULT)
                }) },
            desktopCrossfadeSeconds?.let { seconds -> SettingRow(null, "Crossfade", "Blend audio tracks across the final seconds; videos are not crossfaded.",
                value = if (seconds == 0) "Off" else "${seconds}s",
                choices = CrossfadeAudio.durationChoicesSeconds.map { if (it == 0) "Off" else "${it}s" },
                onChoose = { choice -> CrossfadeAudio.durationChoicesSeconds.firstOrNull { if (it == 0) choice == "Off" else choice == "${it}s" }?.let(onDesktopCrossfadeChange) }) },
            hideDesktopVolumeBar?.let { hidden -> SettingRow(NuvxIcons.SpeakerLow, "Hide volume bar", "Remove the volume slider from the desktop player", toggle = hidden, onClick = { onHideDesktopVolumeBar(!hidden) }) },
            desktopWindowSettings?.let { settings -> SettingRow(NuvxIcons.Settings, "Tray icon", "Add playback controls to the system tray", toggle = settings.showTrayIcon, onClick = {
                val show = !settings.showTrayIcon
                onDesktopWindowSettingsChange(settings.copy(showTrayIcon = show, keepPlayingWhenClosed = settings.keepPlayingWhenClosed && show))
            }) },
            desktopWindowSettings?.takeIf { it.showTrayIcon }?.let { settings -> SettingRow(null, "Keep playing when closed", "Hide nuvx to the system tray instead of stopping playback", toggle = settings.keepPlayingWhenClosed,
                onClick = { onDesktopWindowSettingsChange(settings.copy(keepPlayingWhenClosed = !settings.keepPlayingWhenClosed)) }) },
            onOpenSystemEqualizer?.let { SettingRow(NuvxIcons.Settings, "System equalizer", "Open this device's audio effects for nuvx", onClick = it) },
            SettingRow(NuvxIcons.Settings, "Player diagnostics", "Current playback and engine status", onClick = { showDiagnostics = true }),
        ),
        "STREAMING" to router.available.map { b ->
            SettingRow(null, b.label + if (b == BackendId.INNERTUBEX) " (default)" else "", status(b), value = if (selected == b) "In use" else null,
                onClick = { router.choose(b, store) })
        } + SettingRow(null, "Automatic fallback", "Try the other engine when a request fails", toggle = fallback, onClick = { router.chooseFallback(!fallback, store) }),
        "AUDIO QUALITY" to listOf(SettingRow(null, "Preferred audio quality", "For the next InnerTubeX stream; eXcor may differ. Lossless is unavailable.",
            value = audioQuality.label, choices = AudioQualityPreference.entries.map { it.label }, segmented = true,
            onChoose = { choice -> AudioQualityPreference.entries.firstOrNull { it.label == choice }?.let { router.chooseAudioQuality(it, store) } })),
        "VIDEO QUALITY" to listOf(SettingRow(null, "Preferred video quality", "Applies when the next video loads; the source may return lower.",
            value = videoQuality.label, choices = VideoQualityPreference.entries.map { it.label }, segmented = true,
            onChoose = { choice -> VideoQualityPreference.entries.firstOrNull { it.label == choice }?.let { router.chooseVideoQuality(it, store) } })),
        "APPEARANCE" to listOfNotNull(
            darkMode?.let { enabled -> SettingRow(null, "Dark mode", "Use a dark colour scheme", toggle = enabled,
                onClick = { onDarkModeChange(!enabled) }) },
            SettingRow(null, "Accent palette", "Choose a swatch or generate a new colour set", value = AccentPalettePreference.label(accentHex, accentSwatches),
                onClick = { showAccentDialog = true }),
            SettingRow(null, "Reduce motion", "Reduce decorative motion and animated artwork; system accessibility settings also apply", toggle = reduceMotionPreference,
                onClick = { onReduceMotionChange(!reduceMotionPreference) }),
        ) + listOfNotNull(fullBleed?.let {
            SettingRow(null, "Full-bleed cover art", "The cover fills the top of the player; off shows it as a square", toggle = it, onClick = onFullBleed)
        }, canvasEnabled?.let {
            SettingRow(null, "Community Canvas", "Play community-submitted motion artwork in the player", toggle = it, onClick = { onCanvasEnabled(!it) })
        }),
        "NAVIGATION" to listOf(
            SettingRow(null, "Magnetic navigation", "Spring motion pushes nearby navigation icons aside", toggle = magneticNavigation,
                onClick = { onMagneticNavigation(!magneticNavigation) }),
        ),
        "LYRICS" to listOf(
            SettingRow(NuvxIcons.Lyrics, "Lyrics text size", "Choose the size shown in the player", value = lyricsTextSize.label,
                choices = LyricsTextSizePreference.entries.map { it.label }, onChoose = { choice ->
                    LyricsTextSizePreference.entries.firstOrNull { it.label == choice }?.let(onLyricsTextSizeChange)
                }),
            SettingRow(NuvxIcons.Lyrics, "Prefer word-synced lyrics", "Use source-supplied word timing when available", toggle = lyricSettings.preferWordSynced,
                onClick = { onLyricsChange(lyricSettings.copy(preferWordSynced = !lyricSettings.preferWordSynced)) }),
        ),
        "LIBRARY" to listOfNotNull(
            onLocal?.let { SettingRow(NuvxIcons.Library, "On this device", "Music files stored on this device", onClick = it) },
            onLiked?.let { SettingRow(NuvxIcons.HeartFilled, "Liked songs", "Saved on this device", onClick = it) },
        ),
        "ABOUT" to listOf(
            "Official nuvx website" to "https://nuvx.lol", "Developer: bxane" to "https://github.com/bxanedot",
            "Developer portfolio" to "https://bxane.lol", "Alternative developer portfolio" to "https://guns.lol/bxane",
            "Community Discord" to "https://discord.gg/h4Wsu824Uf", "Contact" to "mailto:bxanee@gmail.com",
            "Source code and licences" to "https://github.com/bxanedot/nuvx",
        ).map { (label, url) -> SettingRow(null, label, onClick = { uri.openUri(url) }) },
    )
    val q = query.trim()
    onBackHandler?.invoke(androidUi && q.isBlank() && androidCategory != null) { androidCategory = null }
    val shown = sections.map { (title, rows) -> title to rows.filter { q.isEmpty() || it.label.contains(q, true) || it.subtitle?.contains(q, true) == true } }
        .filter { (title, rows) -> rows.isNotEmpty() || title == "LYRICS" && lyricProviders.any { it.contains(q, true) } }
    val visibleSections = if (androidUi && q.isBlank() && androidCategory != null) {
        val group = AndroidSettingsCategories.firstOrNull { it.title == androidCategory }
        shown.filter { (title, _) -> title in group?.sections.orEmpty() }
    } else shown
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = if (desktopSettings) 100.dp else 20.dp, end = if (desktopSettings) 0.dp else 20.dp)
        .padding(bottom = desktopAwareBottomPadding())) {
        if (searchQuery == null) Field(query, changeQuery, "Search settings", Modifier.fillMaxWidth().padding(bottom = 8.dp))
        if (androidUi && q.isBlank() && androidCategory == null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NuvxColors.Card)) {
                AndroidSettingsCategories.forEachIndexed { index, category ->
                    if (index > 0) Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(0.5.dp).background(NuvxColors.Divider))
                    SettingsRowView(SettingRow(category.icon, category.title, category.description, onClick = { androidCategory = category.title }), desktop = false, androidUi = androidUi, reduceMotion = reduceMotion)
                }
            }
        } else {
            if (androidUi && q.isBlank()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).clip(CircleShape).clickable { androidCategory = null }
                    .semantics { role = Role.Button; contentDescription = "Back to settings categories" }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(NuvxIcons.Chevron, null, Modifier.size(18.dp).rotate(180f), tint = NuvxColors.Accent)
                    Text(androidCategory.orEmpty(), Modifier.padding(start = 8.dp), color = NuvxColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        visibleSections.forEach { (title, rows) ->
            title?.let { Text(it, color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, top = if (desktopSettings) 28.dp else 20.dp, bottom = 8.dp)) }
                ?: Spacer(Modifier.height(if (desktopSettings) 0.dp else 12.dp))
            Column(Modifier.fillMaxWidth().then(
                if (desktopSettings) Modifier else Modifier.clip(RoundedCornerShape(20.dp)).background(NuvxColors.Card)
            )) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.padding(start = if (desktopSettings) 0.dp else if (r.icon != null) 56.dp else 16.dp)
                        .fillMaxWidth().height(0.5.dp).background(NuvxColors.Divider))
                    SettingsRowView(r, desktopSettings, androidUi, reduceMotion)
                }
            }
            if (title == "LYRICS" && (q.isBlank() || "lyrics".contains(q, true) || lyricProviders.any { it.contains(q, true) })) {
                LyricsProviderSettingsCard(lyricProviders, lyricSettings, onLyricsChange, q)
            }
        }
        }
        if (visibleSections.isEmpty() && !(androidUi && q.isBlank() && androidCategory == null)) Text("No settings match \"$q\".", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
        Text("nuvx · early development · GPL-3.0-only\nInnerTubeX (GPL-3.0) · eXcor (Apache-2.0) · no Google account connected",
            color = NuvxColors.Secondary, fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
    }
    if (showDiagnostics) PlaybackDiagnosticsDialog(
        lines = playbackDiagnostics(selected, router.available, fallback, audioQuality, videoQuality, health, playerState),
        onDismiss = { showDiagnostics = false },
    )
    if (showAccentDialog) AccentPaletteDialog(accentHex, accentSwatches,
        onDismiss = { showAccentDialog = false }, onApply = {
        onAccentPaletteChange(it)
        showAccentDialog = false
    })
}

@Composable internal fun SettingsRowView(r: SettingRow, desktop: Boolean, androidUi: Boolean, reduceMotion: Boolean) {
    val toggleInteraction = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    if (r.segmented && r.choices != null && r.value != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (desktop) 0.dp else 16.dp, vertical = 14.dp)) {
            Text(r.label, color = NuvxColors.Text, fontSize = if (desktop) 16.sp else 17.sp)
            r.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 14.sp) }
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp).then(
                if (desktop) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).background(NuvxColors.ControlTrack).padding(4.dp)
            ), horizontalArrangement = Arrangement.spacedBy(if (desktop) 6.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.choices.forEach { choice ->
                    val active = choice == r.value
                    val shape = RoundedCornerShape(if (desktop) 7.dp else 9.dp)
                    // Measure full labels before deciding which choices fit on each row.
                    Box(Modifier.then(if (desktop) Modifier.widthIn(min = 58.dp) else Modifier.weight(1f).width(IntrinsicSize.Max))
                        .then(if (desktop) Modifier.height(34.dp) else Modifier.heightIn(min = 44.dp)).clip(shape)
                        .background(if (active) NuvxColors.Accent else Color.Transparent)
                        .then(if (desktop && !active) Modifier.border(1.dp, Color.White.copy(0.24f), shape) else Modifier)
                        .focusRing(shape)
                        .clickable { r.onChoose?.invoke(choice) }.semantics { role = Role.RadioButton; selected = active }
                        .padding(horizontal = if (desktop) 14.dp else 6.dp), contentAlignment = Alignment.Center) {
                        Text(choice, color = if (active) NuvxColors.OnAccent else NuvxColors.Secondary, fontSize = if (desktop) 13.sp else 14.sp, maxLines = if (desktop) 1 else Int.MAX_VALUE)
                    }
                }
            }
        }
        return
    }
    val rowShape = RoundedCornerShape(if (desktop) 8.dp else 20.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = if (desktop) 64.dp else 56.dp).then(
        if (r.toggle != null) Modifier.focusRing(rowShape).toggleable(r.toggle, interactionSource = toggleInteraction,
            indication = LocalIndication.current, enabled = r.onClick != null, role = Role.Switch) { r.onClick?.invoke() }
        else if (r.onClick != null || r.choices != null) Modifier.focusRing(rowShape).clickable {
            if (r.choices != null) expanded = true else r.onClick?.invoke()
        } else Modifier)
        .semantics(mergeDescendants = true) { if (r.toggle != null) role = Role.Switch else if (r.onClick != null || r.choices != null) role = Role.Button }
        .padding(horizontal = if (desktop) 0.dp else 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!desktop) r.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = NuvxColors.Text); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(r.label, color = NuvxColors.Text, fontSize = 17.sp)
            r.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 14.sp) }
        }
        when {
            r.choices != null && r.value != null -> Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.value, color = NuvxColors.Accent, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                    Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp).padding(start = 4.dp), tint = NuvxColors.Tertiary)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    r.choices.forEach { choice -> DropdownMenuItem(
                        text = { Text(choice, color = NuvxColors.Text) },
                        onClick = { expanded = false; r.onChoose?.invoke(choice) },
                    ) }
                }
            }
            r.toggle != null -> if (androidUi) IosSwitch(r.toggle, label = r.label, reduceMotion = reduceMotion,
                interaction = toggleInteraction, onCheckedChange = null) else Switch(r.toggle, onCheckedChange = null)
            r.value != null -> Text(r.value, color = NuvxColors.Accent, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            r.onClick != null -> Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp).padding(start = 4.dp), tint = NuvxColors.Tertiary)
        }
    }
}

@Composable
internal fun AccentPaletteDialog(
    initialHex: String,
    initialSwatches: List<String>,
    onDismiss: () -> Unit,
    onApply: (AccentPaletteDraft) -> Unit,
) {
    var draft by remember(initialHex, initialSwatches) { mutableStateOf(AccentPaletteDraft(initialHex, initialSwatches)) }
    var input by remember(initialHex) { mutableStateOf("") }
    var customSelected by remember(initialHex) { mutableStateOf(false) }
    val customValid = customSelected && AccentPalettePreference.isValid(input)
    val previewHex = if (customValid) input else draft.selectedHex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accent palette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a swatch for active controls and highlights. Text and surfaces keep their contrast-safe colours.", color = NuvxColors.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.swatches.forEachIndexed { index, hex ->
                        val chosen = !customSelected && draft.selectedHex.equals(hex, ignoreCase = true)
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.weight(1f).height(42.dp).clip(shape).background(AccentPalettePreference.color(hex))
                            .then(if (chosen) Modifier.border(2.dp, Color.White, shape) else Modifier)
                            .clickable { draft = draft.copy(selectedHex = hex); customSelected = false; input = "" }
                            .semantics {
                                role = Role.RadioButton
                                selected = chosen
                                contentDescription = "Accent swatch ${index + 1}, $hex"
                            })
                    }
                }
                TextButton(onClick = {
                    draft = draft.generate(kotlin.random.Random.Default.nextInt())
                    customSelected = false
                    input = ""
                }) { Text("Generate new palette") }
                Text("Or enter a custom accent colour.", color = NuvxColors.Secondary)
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.removePrefix("#").filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.take(6)
                        customSelected = true
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Custom accent hex colour" },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(AccentPalettePreference.color(previewHex)))
                    Text(if (!customSelected || customValid) "Preview" else "Enter six hex digits", color = if (!customSelected || customValid) NuvxColors.Secondary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !customSelected || customValid, onClick = {
                onApply(draft.copy(selectedHex = AccentPalettePreference.normalize(if (customSelected) input else draft.selectedHex)))
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LyricsProviderSettingsCard(
    providers: List<String>,
    settings: LyricsProviderSettings,
    onChange: (LyricsProviderSettings) -> Unit,
    query: String,
) {
    val enabled = settings.enabled(providers)
    val visible = settings.order.filter { query.isBlank() || "lyrics".contains(query, true) || it.contains(query, true) }
    Spacer(Modifier.height(8.dp))
    if (visible.isEmpty()) {
        Text("No lyrics providers are registered.", color = NuvxColors.Secondary, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        return
    }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(NuvxColors.Card)) {
        Text("Lyrics sources", color = NuvxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
        Text("Choose a default and arrange fallback order.", color = NuvxColors.Secondary, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        visible.forEachIndexed { rowIndex, provider ->
            val index = settings.order.indexOf(provider)
            val active = provider in enabled
            val isDefault = provider == settings.preferredSource
            Row(
                Modifier.fillMaxWidth().reorderable(index, settings.order.lastIndex) { from, to ->
                    val moved = settings.order.toMutableList().apply { add(to, removeAt(from)) }
                    onChange(settings.copy(order = moved))
                }.padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clickable(enabled = active) {
                    onChange(settings.copy(preferredSource = provider))
                }.semantics { role = Role.Button; selected = isDefault }.padding(vertical = 10.dp)) {
                    Text(provider, color = if (active) NuvxColors.Text else NuvxColors.Tertiary, fontSize = 14.sp, maxLines = 1)
                    if (isDefault) Text("Default", color = NuvxColors.Accent, fontSize = 12.sp)
                }
                IconButton(enabled = index > 0, onClick = {
                        if (index > 0) onChange(settings.copy(order = settings.order.toMutableList().apply { add(index - 1, removeAt(index)) }))
                    }, modifier = Modifier.semantics { contentDescription = "Move $provider up" }) {
                    Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp).rotate(-90f), tint = NuvxColors.Secondary)
                }
                IconButton(enabled = index < settings.order.lastIndex, onClick = {
                        if (index < settings.order.lastIndex) onChange(settings.copy(order = settings.order.toMutableList().apply { add(index + 1, removeAt(index)) }))
                    }, modifier = Modifier.semantics { contentDescription = "Move $provider down" }) {
                    Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp).rotate(90f), tint = NuvxColors.Secondary)
                }
                Switch(
                    checked = active,
                    enabled = active || enabled.size > 1,
                    onCheckedChange = { checked ->
                        val disabled = if (checked) settings.disabled - provider else settings.disabled + provider
                        onChange(settings.copy(disabled = disabled))
                    },
                    modifier = Modifier.semantics { contentDescription = "Use $provider" },
                )
            }
            if (rowIndex < visible.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(0.08f)))
        }
    }
}

@Composable private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, ime: ImeAction = ImeAction.Done, onIme: () -> Unit = {}) =
    Box(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(NuvxColors.Card).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = NuvxColors.Tertiary, fontSize = 17.sp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = placeholder }, singleLine = true,
            textStyle = TextStyle(color = NuvxColors.Text, fontSize = 17.sp), cursorBrush = SolidColor(NuvxColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ime), keyboardActions = KeyboardActions(onAny = { onIme() }))
    }

@Composable private fun PillButton(text: String, enabled: Boolean, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.heightIn(min = 52.dp).clip(CircleShape).background(if (primary) Color.White.copy(if (enabled) 1f else 0.4f) else NuvxColors.Card)
        .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color.Black else NuvxColors.Text.copy(if (enabled) 1f else 0.4f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }

@Composable private fun TogetherPage(together: ListenTogether, store: KeyValueStore) {
    val ui by together.ui.collectAsState()
    var name by remember { mutableStateOf(store.get("listenName") ?: "") }
    var code by remember { mutableStateOf("") }
    val displayName = name.trim().ifEmpty { "Listener" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = desktopAwareBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!ui.inRoom) {
            Text("Start a room and share its code. Everyone hears the same song at the same moment, each streaming on their own device.", color = NuvxColors.Secondary, fontSize = 16.sp)
            Field(name, { name = it.take(32); store.put("listenName", name) }, "Your name", Modifier.fillMaxWidth())
            PillButton("Start a room", !ui.connecting, primary = true, Modifier.fillMaxWidth()) { together.create(displayName) }
            Text("OR JOIN ONE", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) }, "Room code", Modifier.weight(1f), ImeAction.Go) { if (code.length == 6) together.join(code, displayName) }
                PillButton("Join", code.length == 6 && !ui.connecting, primary = false) { together.join(code, displayName) }
            }
            if (ui.connecting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NuvxColors.Accent, trackColor = Color.White.copy(0.1f))
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(NuvxColors.Card).padding(20.dp)) {
                Text("ROOM CODE", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp)
                Text(ui.code.orEmpty(), color = NuvxColors.Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                    modifier = Modifier.semantics { contentDescription = "Room code ${ui.code.orEmpty().toList().joinToString(" ")}" })
                Text(if (ui.isHost) "You're the host. What you play, everyone hears." else "Following ${ui.members.firstOrNull { it.id == ui.host }?.name ?: "the host"}. The host controls playback.",
                    color = NuvxColors.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                ui.playback?.takeIf { it.trackId != null }?.let {
                    Text("Now: ${it.title ?: "Shared track"}" + it.artists.takeIf { a -> a.isNotEmpty() }?.joinToString(", ", " · ").orEmpty(),
                        color = NuvxColors.Secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            SectionHeader("${ui.members.size} listening", Modifier.padding(start = 0.dp))
            ui.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(NuvxColors.Card), contentAlignment = Alignment.Center) {
                        Text(m.name.take(1).uppercase(), color = NuvxColors.Text, fontWeight = FontWeight.Bold)
                    }
                    Text(m.name + (if (m.id == ui.you) " (you)" else ""), color = NuvxColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    if (m.id == ui.host) Text("Host", color = NuvxColors.Accent, fontSize = 14.sp)
                }
            }
            PillButton("Leave room", true, primary = false, Modifier.padding(top = 12.dp)) { together.leave() }
        }
        ui.error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 15.sp) }
    }
}

@Composable private fun LikedPage(history: History, state: PlayerState, player: NuvxPlayer) {
    val liked by history.liked.collectAsState()
    if (liked.isEmpty()) Text("Songs you like show up here. Tap ♡ on the player to like one.", color = NuvxColors.Secondary, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
    else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        items(liked.size) { i -> TrackRow(liked[i], active = state.current?.videoId == liked[i].videoId) { player.playQueue(liked, i) } }
    }
}

@Composable private fun PlaybackHistoryPage(history: History, state: PlayerState, player: NuvxPlayer) {
    val played by history.played.collectAsState()
    if (played.isEmpty()) Text("Songs you play on this device will appear here.", color = NuvxColors.Secondary, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
    else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = desktopAwareBottomPadding())) {
        items(played.size) { index -> TrackRow(played[index], active = state.current?.videoId == played[index].videoId) { player.playQueue(played, index) } }
    }
}

@Composable private fun AlbumShell(onBack: () -> Unit, body: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        GlassCircleButton("Back", onBack, size = 44.dp) {
            Icon(NuvxIcons.Chevron, null, Modifier.size(20.dp).graphicsLayerMirror(), tint = NuvxColors.Text)
        }
    }
    Box(Modifier.weight(1f)) { body() }
}
