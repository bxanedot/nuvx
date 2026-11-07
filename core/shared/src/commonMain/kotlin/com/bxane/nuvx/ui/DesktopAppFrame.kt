package com.bxane.nuvx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.core.BackendRouter
import com.bxane.nuvx.core.Lyrics
import com.bxane.nuvx.core.LyricsChain
import com.bxane.nuvx.core.LyricsProviderSettings
import com.bxane.nuvx.core.LyricsTimingOffset
import com.bxane.nuvx.core.LyricsTextSizePreference
import com.bxane.nuvx.core.NuvxPlayer
import com.bxane.nuvx.core.PlayerState
import com.bxane.nuvx.core.RepeatMode
import com.bxane.nuvx.core.Track
import kotlinx.coroutines.CancellationException

internal data class DesktopPanelSizing(val sidebar: Dp, val compactSidebar: Boolean, val listeningPane: Dp?)

internal fun desktopPanelSizing(windowWidth: Dp): DesktopPanelSizing {
    val compactSidebar = windowWidth < 760.dp
    val sidebar = if (compactSidebar) 64.dp else (windowWidth - 880.dp).coerceIn(144.dp, 200.dp)
    val listeningPane = if (windowWidth < 1040.dp) null else (windowWidth - sidebar - 560.dp).coerceAtMost(320.dp)
    return DesktopPanelSizing(sidebar, compactSidebar, listeningPane)
}

@Composable
internal fun DesktopAppFrame(
    title: String,
    selectedTab: Int,
    searchSelected: Boolean,
    selectedPage: String?,
    searchQuery: String?,
    settingsQuery: String?,
    onSettingsQueryChange: (String) -> Unit,
    player: NuvxPlayer,
    state: PlayerState,
    lyrics: LyricsChain,
    lyricSettings: LyricsProviderSettings,
    lyricsTextSize: LyricsTextSizePreference,
    reduceMotion: Boolean,
    magneticNavigation: Boolean,
    onLyricsSettingsChange: (LyricsProviderSettings) -> Unit,
    lyricsOffsetMs: Long,
    onLyricsOffsetChange: (Long) -> Unit,
    onSelectHome: () -> Unit,
    onSelectSearch: () -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onSelectExplore: () -> Unit,
    onSelectLibrary: () -> Unit,
    onSelectHistory: () -> Unit,
    onSelectLocal: (() -> Unit)?,
    onSelectTogether: () -> Unit,
    onSelectSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
    hideVolumeBar: Boolean = false,
    content: @Composable () -> Unit,
) {
    var inspectorOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF555555))) {
        val sizing = desktopPanelSizing(maxWidth)
        val inspectorWidth = sizing.listeningPane
        val inspectorAvailable = inspectorWidth != null
        val showInspector = inspectorOpen && inspectorAvailable
        Row(Modifier.fillMaxSize()) {
            DesktopSidebar(
                sizing.sidebar, sizing.compactSidebar, selectedTab, searchSelected, selectedPage, settingsQuery != null,
                reduceMotion, magneticNavigation,
                onSelectHome, onSelectSearch, onSubmitSearch, searchQuery, onSelectExplore, onSelectLibrary,
                onSelectHistory, onSelectLocal, onSelectSettings,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DesktopPlayerBar(
                    player, state, hideVolumeBar, inspectorAvailable, inspectorOpen, reduceMotion,
                    onToggleInspector = { inspectorOpen = !inspectorOpen }, onOpenPlayer = onOpenPlayer,
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (selectedPage != null || settingsQuery != null) {
                            DesktopHeader(
                                title, showSearchAction = !searchSelected && settingsQuery == null, onSelectSearch, onSelectTogether,
                                settingsQuery = settingsQuery, onSettingsQueryChange = onSettingsQueryChange,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    }
                    if (showInspector) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
                        DesktopListeningPane(inspectorWidth, state, player, lyrics, lyricSettings, lyricsTextSize, reduceMotion, onLyricsSettingsChange, lyricsOffsetMs, onLyricsOffsetChange)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopSidebar(
    width: Dp,
    compact: Boolean,
    selectedTab: Int,
    searchSelected: Boolean,
    selectedPage: String?,
    settingsOpen: Boolean,
    reduceMotion: Boolean,
    magneticNavigation: Boolean,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onSubmitSearch: (String?) -> Unit,
    searchQuery: String?,
    onExplore: () -> Unit,
    onLibrary: () -> Unit,
    onHistory: () -> Unit,
    onLocal: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    var searchText by remember(searchQuery) { mutableStateOf(searchQuery.orEmpty()) }
    val magneticSelectedIndex = when {
        searchSelected -> 3
        selectedPage == "History" -> 4
        selectedPage == "On this device" -> 5
        settingsOpen -> 6
        else -> selectedTab.coerceIn(0, 2)
    }
    Column(Modifier.width(width).fillMaxHeight().background(Color(0xFF3B3B3B)).padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvxBrandMark("nuvx", Modifier.size(38.dp))
            if (!compact) Text("nuvx", color = NuvxColors.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303033))
                .focusRing(RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NuvxIcons.Search, null, Modifier.size(15.dp), tint = NuvxColors.Secondary)
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Search music" },
                    singleLine = true,
                    textStyle = TextStyle(color = NuvxColors.Text, fontSize = 13.sp),
                    cursorBrush = SolidColor(NuvxColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(searchText.trim().takeIf(String::isNotEmpty)) }),
                    decorationBox = { inner ->
                        Box {
                            if (searchText.isEmpty()) Text("Search", color = NuvxColors.Secondary, fontSize = 13.sp)
                            inner()
                        }
                    },
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(14.dp))
            DesktopNavItem(NuvxIcons.Home, "Home", 0, magneticSelectedIndex, selectedTab == 0 && !searchSelected && selectedPage == null, onHome, reduceMotion, magneticNavigation, compact, "house.fill")
            DesktopNavItem(NuvxIcons.Explore, "Explore", 1, magneticSelectedIndex, selectedTab == 1 && !searchSelected && selectedPage == null, onExplore, reduceMotion, magneticNavigation, compact, "square.grid.2x2.fill")
            DesktopNavItem(NuvxIcons.Library, "Your library", 2, magneticSelectedIndex, selectedTab == 2 && !searchSelected && selectedPage == null, onLibrary, reduceMotion, magneticNavigation, compact, "music.note.square.stack.fill")
            DesktopNavItem(NuvxIcons.Search, "Search", 3, magneticSelectedIndex, searchSelected, onSearch, reduceMotion, magneticNavigation, compact, "magnifyingglass")
            if (!compact) Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.14f)))
            if (!compact) Spacer(Modifier.height(10.dp))
            DesktopNavItem(NuvxIcons.History, "History", 4, magneticSelectedIndex, selectedPage == "History", onHistory, reduceMotion, magneticNavigation, compact, "clock.arrow.circlepath")
            if (onLocal != null) DesktopNavItem(NuvxIcons.Library, "On this device", 5, magneticSelectedIndex, selectedPage == "On this device", onLocal, reduceMotion, magneticNavigation, compact, "iphone")
        }
        DesktopNavItem(NuvxIcons.Settings, "Settings", 6, magneticSelectedIndex, settingsOpen, onSettings, reduceMotion, magneticNavigation, compact, "gearshape.fill")
    }
}

@Composable
private fun DesktopNavItem(icon: ImageVector, label: String, itemIndex: Int, selectedIndex: Int, selected: Boolean, onClick: () -> Unit,
    reduceMotion: Boolean, magneticNavigation: Boolean, compact: Boolean, symbolName: String) {
    val background by animateColorAsState(
        if (selected) Color(0xFF252529) else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(150),
        label = "desktopNavSelection",
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.liquidGlass(RoundedCornerShape(10.dp)) else Modifier)
            .background(background.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .focusRing(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Tab; this.selected = selected }
            .padding(horizontal = if (compact) 0.dp else 10.dp, vertical = 8.dp),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (magneticNavigation && !reduceMotion) MagneticNavigationIcon(icon, itemIndex, selectedIndex, 20.dp,
            if (selected) NuvxColors.Accent else NuvxColors.Secondary, vertical = true, symbolName = symbolName, selected = selected)
        else NuvxGlyphIcon(symbolName, Modifier.size(20.dp), tint = if (selected) NuvxColors.Accent else NuvxColors.Secondary,
            selected = selected, trigger = selected, animateOnTrigger = selected, motionEnabled = !reduceMotion)
        if (!compact) Text(label, color = if (selected) NuvxColors.Text else NuvxColors.Secondary, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun DesktopHeader(
    title: String,
    showSearchAction: Boolean,
    onSearch: () -> Unit,
    onTogether: () -> Unit,
    settingsQuery: String?,
    onSettingsQueryChange: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (settingsQuery != null) 100.dp else 24.dp,
        end = if (settingsQuery != null) 0.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = NuvxColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        settingsQuery?.let { DesktopSettingsSearch(it, onSettingsQueryChange) }
        if (showSearchAction) {
            GlassIconButton(NuvxIcons.Search, "Search", 44.dp, onSearch, liquid = true)
            Spacer(Modifier.width(8.dp))
        }
        if (settingsQuery == null) GlassIconButton(NuvxIcons.Together, "Listen together", 44.dp, onTogether, liquid = true)
    }
}

@Composable
private fun DesktopSettingsSearch(value: String, onValueChange: (String) -> Unit) {
    Row(Modifier.width(200.dp).height(40.dp).clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF242427)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(NuvxIcons.Search, null, Modifier.size(17.dp), tint = NuvxColors.Secondary)
        Spacer(Modifier.width(8.dp))
        BasicTextField(value, onValueChange, singleLine = true,
            modifier = Modifier.weight(1f).focusRing(RoundedCornerShape(6.dp)).semantics { contentDescription = "Search settings" },
            textStyle = LocalTextStyle.current.copy(color = NuvxColors.Text, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(NuvxColors.Accent),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search settings", color = NuvxColors.Secondary, fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun DesktopListeningPane(
    width: Dp,
    state: PlayerState,
    player: NuvxPlayer,
    chain: LyricsChain,
    settings: LyricsProviderSettings,
    lyricsTextSize: LyricsTextSizePreference,
    reduceMotion: Boolean,
    onSettingsChange: (LyricsProviderSettings) -> Unit,
    lyricsOffsetMs: Long,
    onLyricsOffsetChange: (Long) -> Unit,
) {
    var lyricsSelected by remember { mutableStateOf(false) }
    Column(Modifier.width(width).fillMaxHeight().background(Color(0xFF101013)).padding(18.dp)) {
        Text("Listening", color = NuvxColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
        DesktopCurrentTrackCard(state.current)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NuvxColors.Card).padding(3.dp)) {
            listOf("Queue", "Lyrics").forEachIndexed { index, label ->
                val selected = (index == 1) == lyricsSelected
                Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Color(0xFF36363A) else Color.Transparent)
                    .focusRing(RoundedCornerShape(8.dp)).clickable { lyricsSelected = index == 1 }
                    .semantics { role = Role.Tab; this.selected = selected },
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) NuvxColors.Text else NuvxColors.Secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (lyricsSelected) DesktopLyricsInspector(state, player, chain, settings, lyricsTextSize, reduceMotion, onSettingsChange, lyricsOffsetMs, onLyricsOffsetChange)
        else DesktopQueueInspector(state, player)
    }
}

@Composable
private fun DesktopCurrentTrackCard(track: Track?) {
    Text("NOW PLAYING", color = NuvxColors.Secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp, modifier = Modifier.padding(bottom = 9.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NuvxColors.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkTile(track, Modifier.size(58.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track?.title ?: "Nothing playing", color = NuvxColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artistLine ?: "Choose music from Home, Search or Explore", color = NuvxColors.Secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DesktopQueueInspector(state: PlayerState, player: NuvxPlayer) {
    Text("Up next", color = NuvxColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    if (state.queue.isEmpty()) Text("Your queue is empty.", color = NuvxColors.Secondary, fontSize = 14.sp)
    else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val upNext = state.queue.indices.filter { it != state.index }
        itemsIndexed(upNext, key = { _, index -> "$index-${state.queue[index].videoId}" }) { _, index ->
            val track = state.queue[index]
            DesktopQueueRow(
                track, index == state.index,
                Modifier.reorderable(index, state.queue.lastIndex, player::move),
                onRemove = if (index == state.index) null else ({ player.remove(index) }),
                onClick = { player.skipTo(index) },
            )
        }
    }
}

@Composable
private fun DesktopQueueRow(track: Track, active: Boolean, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) Color(0xFF242427) else Color.Transparent)
        .clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkTile(track, Modifier.size(42.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(track.title, color = if (active) NuvxColors.Accent else NuvxColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistLine, color = NuvxColors.Secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onRemove?.let { PlainIconButton(NuvxIcons.Close, "Remove ${track.title} from the queue", 38.dp, iconSize = 16.dp, onClick = it) }
        track.durationMs?.let { Text(clock(it), color = NuvxColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)) }
    }
}

@Composable
private fun DesktopLyricsInspector(
    state: PlayerState,
    player: NuvxPlayer,
    chain: LyricsChain,
    settings: LyricsProviderSettings,
    lyricsTextSize: LyricsTextSizePreference,
    reduceMotion: Boolean,
    onSettingsChange: (LyricsProviderSettings) -> Unit,
    lyricsOffsetMs: Long,
    onLyricsOffsetChange: (Long) -> Unit,
) {
    val track = state.current
    var found by remember(track?.videoId) { mutableStateOf<com.bxane.nuvx.core.SourcedLyrics?>(null) }
    var status by remember(track?.videoId) { mutableStateOf("") }
    LaunchedEffect(track?.videoId, state.durationMs, settings) {
        found = null
        if (track == null) { status = "Play something to see lyrics here."; return@LaunchedEffect }
        val duration = track.durationMs ?: state.durationMs.takeIf { it > 0 }
        if (duration == null) { status = "Waiting for track details…"; return@LaunchedEffect }
        status = if (settings.enabled(chain.providerNames).isEmpty()) "No lyrics sources are enabled." else "Finding lyrics…"
        try {
            found = chain.find(
                track.copy(durationMs = duration),
                preferredSource = settings.preferredSource,
                providerOrder = settings.order,
                disabledSources = settings.disabled,
                preferWordSynced = settings.preferWordSynced,
            )
            status = if (found == null) "No lyrics for this song." else ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Lyrics couldn't be loaded." }
    }
    val enabled = settings.enabled(chain.providerNames)
    val selected = settings.preferredSource ?: enabled.firstOrNull().orEmpty()
    var sourceMenu by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(18.dp)).background(NuvxColors.Card)
            .focusRing(RoundedCornerShape(18.dp)).clickable(enabled.isNotEmpty()) { sourceMenu = true }
            .semantics { role = Role.Button; contentDescription = "Lyrics source: $selected" }
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Source: ${selected.ifEmpty { "none" }}", color = NuvxColors.Text, fontSize = 12.sp)
            Icon(NuvxIcons.Chevron, null, Modifier.padding(start = 4.dp).size(13.dp), tint = NuvxColors.Secondary)
        }
        DropdownMenu(sourceMenu, { sourceMenu = false }, containerColor = NuvxColors.Card) {
            enabled.forEach { provider ->
                DropdownMenuItem({ Text((if (provider == selected) "✓  " else "") + provider) }, {
                    onSettingsChange(settings.copy(preferredSource = provider))
                    sourceMenu = false
                })
            }
        }
    }
    found?.let { Text("Lyrics · ${it.source}", color = NuvxColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)) }
    when (val lyrics = found?.lyrics) {
        is Lyrics.Synced -> Column(Modifier.fillMaxSize()) {
            LyricsTimingControls(lyricsOffsetMs, onLyricsOffsetChange)
            val shifted = remember(lyrics, lyricsOffsetMs) { LyricsTimingOffset.shift(lyrics, lyricsOffsetMs) }
            SyncedLyrics(shifted, state.positionMs, reduceMotion, player::seekTo, compact = true, fontScale = lyricsTextSize.scale, modifier = Modifier.weight(1f))
        }
        is Lyrics.Plain -> LazyColumn(Modifier.fillMaxSize()) { item { Text(lyrics.text, color = NuvxColors.Text, fontSize = (16f * lyricsTextSize.scale).sp, lineHeight = (24f * lyricsTextSize.scale).sp) } }
        Lyrics.Instrumental -> Text("Instrumental", color = NuvxColors.Secondary, fontSize = 16.sp)
        null -> Text(status, color = NuvxColors.Secondary, fontSize = 14.sp)
    }
}

@Composable
private fun DesktopPlayerBar(
    player: NuvxPlayer,
    state: PlayerState,
    hideVolumeBar: Boolean,
    showInspectorAction: Boolean,
    inspectorOpen: Boolean,
    reduceMotion: Boolean,
    onToggleInspector: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val track = state.current
    Row(Modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT_DP.dp).liquidGlass(RoundedCornerShape(0.dp))
        .border(0.5.dp, Color.White.copy(alpha = 0.11f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NuvxBrandMark("nuvx", Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            PlainIconButton(NuvxIcons.Shuffle, "Shuffle upcoming songs", 32.dp, iconSize = 18.dp, enabled = state.queue.size > state.index + 1, onClick = player::shuffleUpNext)
            PlainIconButton(NuvxIcons.Back, "Previous", 32.dp, iconSize = 18.dp, enabled = state.queue.isNotEmpty(), onClick = player::previous)
            PlayPauseIconButton(state.isPlaying, player::togglePlay, 36.dp, iconSize = 21.dp, enabled = track != null, reduceMotion = reduceMotion)
            PlainIconButton(NuvxIcons.Forward, "Next", 32.dp, iconSize = 18.dp, enabled = state.index < state.queue.lastIndex, onClick = player::next)
            val nextRepeat = when (state.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            PlainIconButton(
                if (state.repeat == RepeatMode.ONE) NuvxIcons.RepeatOne else NuvxIcons.Repeat,
                when (state.repeat) {
                    RepeatMode.OFF -> "Turn on repeat"
                    RepeatMode.ALL -> "Repeat all; switch to repeat one"
                    RepeatMode.ONE -> "Repeat one; turn repeat off"
                },
                32.dp,
                iconSize = 18.dp,
                enabled = track != null,
                onClick = { player.setRepeat(nextRepeat) },
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF242427))
                .clickable(enabled = track != null, onClick = onOpenPlayer)
                .semantics { contentDescription = track?.let { "Now playing: ${it.title}, ${it.artistLine}" } ?: "Nothing playing" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (track == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(NuvxIcons.Note, null, Modifier.size(17.dp), tint = NuvxColors.Secondary)
                }
            } else {
                ArtworkTile(track, Modifier.padding(start = 4.dp).size(24.dp))
                Column(Modifier.widthIn(min = 80.dp, max = 176.dp).padding(start = 8.dp)) {
                    Text(track.title, color = NuvxColors.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artistLine, color = NuvxColors.Secondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Slider(
                    value = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (state.durationMs > 0) player.seekTo((it * state.durationMs).toLong()) },
                    enabled = state.durationMs > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(24.dp).semantics {
                        contentDescription = "Playback position: ${clock(state.positionMs)} of ${clock(state.durationMs)}"
                    },
                )
            }
        }
        if (showInspectorAction) {
            Spacer(Modifier.width(8.dp))
            GlassIconButton(NuvxIcons.Lyrics, if (inspectorOpen) "Hide listening panel" else "Show queue and lyrics", 34.dp, onToggleInspector)
        }
        if (!hideVolumeBar) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NuvxIcons.SpeakerLow, null, Modifier.size(16.dp), tint = NuvxColors.Secondary)
                Slider(state.volume, player::setVolume, Modifier.weight(1f).height(24.dp).semantics { contentDescription = "Volume" })
                Icon(NuvxIcons.SpeakerHigh, null, Modifier.size(16.dp), tint = NuvxColors.Secondary)
            }
        }
    }
}
