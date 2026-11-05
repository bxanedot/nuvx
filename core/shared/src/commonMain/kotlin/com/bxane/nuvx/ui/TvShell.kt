package com.bxane.nuvx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.bxane.nuvx.core.*
import com.bxane.nuvx.core.Collection

private data class TvTab(val destination: TvDestination, val label: String, val symbolName: String)

private val tvTabs = listOf(
    TvTab(TvDestination.HOME, "Home", "house.fill"),
    TvTab(TvDestination.EXPLORE, "Explore", "safari.fill"),
    TvTab(TvDestination.LIBRARY, "Library", "books.vertical.fill"),
    TvTab(TvDestination.NOW_PLAYING, "Now playing", "play.fill"),
    TvTab(TvDestination.SEARCH, "Search", "magnifyingglass"),
    TvTab(TvDestination.TOGETHER, "Listen together", "person.2.fill"),
    TvTab(TvDestination.SETTINGS, "Settings", "gearshape.fill"),
)

@Composable
internal fun TvShell(
    router: BackendRouter,
    history: History,
    state: PlayerState,
    player: NuvxPlayer,
    lyrics: LyricsChain,
    localLibrary: LocalMusicLibrary?,
    reduceMotion: Boolean,
    onBackHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)?,
    sleep: SleepTimer? = null,
    canvas: Pair<CommunityCanvas, @Composable (url: String, modifier: Modifier) -> Unit>? = null,
    pageContent: @Composable (TvSubpage) -> Unit,
) {
    var navigation by remember { mutableStateOf(TvNavigationState()) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var currentCollection by remember { mutableStateOf<Collection?>(null) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    val openPage: (TvSubpage) -> Unit = { page ->
        val destination = when (page) {
            TvSubpage.TOGETHER -> TvDestination.TOGETHER
            TvSubpage.SETTINGS -> TvDestination.SETTINGS
            TvSubpage.QUEUE, TvSubpage.LIKED, TvSubpage.LOCAL_LIBRARY -> TvDestination.LIBRARY
        }
        currentCollection = null
        navigation = navigation.copy(destination = destination, pageReturn = navigation.destination, subpage = page, albumOpen = false)
    }
    val selectTab: (TvDestination) -> Unit = { destination ->
        if (destination != TvDestination.NOW_PLAYING) currentCollection = null
        navigation = when (destination) {
            TvDestination.TOGETHER -> navigation.copy(destination = destination, pageReturn = navigation.destination, subpage = TvSubpage.TOGETHER, albumOpen = false)
            TvDestination.SETTINGS -> navigation.copy(destination = destination, pageReturn = navigation.destination, subpage = TvSubpage.SETTINGS, albumOpen = false)
            TvDestination.NOW_PLAYING -> if (navigation.destination == destination) navigation else navigation.copy(destination = destination, playerReturn = navigation.destination)
            else -> navigation.copy(destination = destination, subpage = null, albumOpen = false)
        }
    }
    val canGoBack = navigation.destination != TvDestination.HOME || navigation.albumOpen || navigation.subpage != null
    onBackHandler?.invoke(canGoBack) { navigation = navigation.back() }

    Column(Modifier.fillMaxSize().background(NuvxColors.Background).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).focusGroup().glass(RoundedCornerShape(36.dp)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tvTabs.forEachIndexed { index, tab ->
                    TvTabButton(tab, navigation.destination == tab.destination, if (index == 0) initialFocus else null, motionEnabled = !reduceMotion) {
                        selectTab(tab.destination)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxSize().widthIn(max = 1440.dp)) {
                when {
                    navigation.destination == TvDestination.NOW_PLAYING -> NowPlayingSheet(player, state, lyrics, history, router, reduceMotion, sleep = sleep, canvas = canvas,
                        // Album/artist links from the player open over Search, so Back lands somewhere useful.
                        onOpen = { c -> currentCollection = c; navigation = navigation.copy(destination = TvDestination.SEARCH, albumOpen = true, subpage = null) }) {
                        navigation = navigation.back()
                    }
                    navigation.albumOpen -> Column(Modifier.fillMaxSize()) {
                        TvBackButton("Back to search") { navigation = navigation.back() }
                        Box(Modifier.weight(1f)) {
                            currentCollection?.let {
                                // Artists get their own page; an album opened from it replaces the artist (Back returns to search).
                                if (it.kind == Collection.Kind.ARTIST) ArtistPage(router, player, state, it) { next -> currentCollection = next }
                                else AlbumPage(router, player, state, it)
                            }
                                ?: Text("Album unavailable.", color = NuvxColors.Secondary, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    navigation.subpage != null -> Column(Modifier.fillMaxSize()) {
                        TvBackButton("Back") { navigation = navigation.back() }
                        Box(Modifier.weight(1f)) { pageContent(navigation.subpage!!) }
                    }
                    navigation.destination == TvDestination.HOME -> HomeScreen(history, router, player, { openPage(TvSubpage.TOGETHER) }, { openPage(TvSubpage.SETTINGS) })
                    navigation.destination == TvDestination.EXPLORE -> ExploreScreen({ query ->
                        searchQuery = query
                        navigation = navigation.copy(destination = TvDestination.SEARCH, albumOpen = false, subpage = null)
                    }, router) { openPage(TvSubpage.SETTINGS) }
                    navigation.destination == TvDestination.LIBRARY -> LibraryScreen(
                        history, state, player,
                        { openPage(TvSubpage.QUEUE) }, { openPage(TvSubpage.LIKED) }, { openPage(TvSubpage.TOGETHER) }, { openPage(TvSubpage.SETTINGS) },
                        onLocalLibrary = if (localLibrary != null) ({ openPage(TvSubpage.LOCAL_LIBRARY) }) else null,
                        reduceMotion = reduceMotion,
                    )
                    navigation.destination == TvDestination.SEARCH -> SearchScreen(router, player, history, searchQuery, { collection ->
                        currentCollection = collection
                        navigation = navigation.copy(albumOpen = true)
                    }) { openPage(TvSubpage.SETTINGS) }
                    else -> Text("Play something to see it here.", color = NuvxColors.Secondary, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun TvTabButton(tab: TvTab, selected: Boolean, focusRequester: FocusRequester?, motionEnabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.22f) else if (selected) Color.White.copy(alpha = 0.13f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; contentDescription = tab.label; role = Role.Tab }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvxGlyphIcon(tab.symbolName, Modifier.size(20.dp), tint = if (focused || selected) NuvxColors.Accent else NuvxColors.Secondary,
            selected = focused || selected, trigger = focused || selected, motionEnabled = motionEnabled)
        Text(tab.label, color = if (focused || selected) NuvxColors.Text else NuvxColors.Secondary, fontSize = 14.sp)
    }
}

@Composable
private fun TvBackButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }.clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NuvxIcons.Back, null, Modifier.size(18.dp), tint = NuvxColors.Text)
        Text(label, Modifier.padding(start = 8.dp), color = NuvxColors.Text, fontSize = 15.sp)
    }
}
