package com.bxane.nuvx.ui

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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

private val Tabs = listOf(Tab("Home", NuvxIcons.Home), Tab("Explore", NuvxIcons.Explore), Tab("Library", NuvxIcons.Library))
private enum class Page(val title: String) { TOGETHER("Listen Together"), SETTINGS("Settings"), QUEUE("Queue"), LIKED("Liked songs"), LOCAL_LIBRARY("On this device") }

@Composable
fun NuvxApp(
    router: BackendRouter, store: KeyValueStore, player: NuvxPlayer, lyrics: LyricsChain, watch: Boolean, reduceMotion: Boolean = false, tv: Boolean = false,
    localLibrary: LocalMusicLibrary? = null,
    /** Platform back integration (Android passes BackHandler). Called every composition with whether back has somewhere to go. */
    onBackHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    val state by player.state.collectAsState()
    var welcomed by remember { mutableStateOf(store.get("welcomeSeen") == "true") }
    // shortcut: the room and history live as long as this composition (an Android activity recreation restarts them); move to the app graph when that matters.
    val scope = rememberCoroutineScope()
    val together = remember(player) { ListenTogether(player, scope) }
    val history = remember(store) { History(store) }
    LaunchedEffect(state.current?.videoId) { state.current?.let(history::played) }

    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<Page?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<Collection?>(null) }
    var artist by remember { mutableStateOf<Collection?>(null) }
    // Scrolling down folds the tab bar into a compact row (tab button · mini player · search); scrolling up restores it.
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

    MaterialTheme(colorScheme = darkColorScheme(primary = NuvxColors.Accent, background = NuvxColors.Background, surface = NuvxColors.Card, onSurface = NuvxColors.Text)) {
        Surface(Modifier.fillMaxSize(), color = NuvxColors.Background) {
            when {
                watch -> WatchApp(router, store, player)
                !welcomed -> Welcome { store.put("welcomeSeen", "true"); welcomed = true }
                tv -> TvShell(router, history, state, player, lyrics, localLibrary, reduceMotion, onBackHandler) { tvPage ->
                    when (tvPage) {
                        TvSubpage.TOGETHER -> TogetherPage(together, store)
                        TvSubpage.SETTINGS -> SettingsPage(router, store)
                        TvSubpage.QUEUE -> QueuePage(state, player)
                        TvSubpage.LIKED -> LikedPage(history, state, player)
                        TvSubpage.LOCAL_LIBRARY -> if (localLibrary != null) LocalLibraryScreen(localLibrary, player) else Unit
                    }
                }
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    val select = { i: Int -> tab = i; searching = false; page = null; album = null; artist = null }
                    val openSearch = { q: String? -> searchQuery = q; searching = true; page = null; album = null; artist = null }
                    val openPage = { p: Page -> page = p }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().safeDrawingPadding()) {
                            val content = Modifier.align(Alignment.TopCenter).fillMaxSize().then(if (wide) Modifier.widthIn(max = 1100.dp) else Modifier)
                            Box(content.nestedScroll(scrollWatch)) {
                                when {
                                    album != null -> AlbumShell({ album = null }) { AlbumPage(router, player, state, album!!) }
                                    artist != null -> AlbumShell({ artist = null }) { ArtistPage(router, player, state, artist!!, open) }
                                    page != null -> SubPage(page!!, { page = null }) {
                                        when (page!!) {
                                            Page.TOGETHER -> TogetherPage(together, store)
                                            Page.SETTINGS -> SettingsPage(router, store, { openPage(Page.TOGETHER) }, { openPage(Page.LIKED) },
                                                if (localLibrary != null) ({ openPage(Page.LOCAL_LIBRARY) }) else null)
                                            Page.QUEUE -> QueuePage(state, player)
                                            Page.LIKED -> LikedPage(history, state, player)
                                            Page.LOCAL_LIBRARY -> if (localLibrary != null) LocalLibraryScreen(localLibrary, player) else Unit
                                        }
                                    }
                                    searching -> SearchScreen(router, player, history, searchQuery, open) { openPage(Page.SETTINGS) }
                                    tab == 0 -> HomeScreen(history, router, player, { openPage(Page.TOGETHER) }) { openPage(Page.SETTINGS) }
                                    tab == 1 -> ExploreScreen({ openSearch(it) }, router) { openPage(Page.SETTINGS) }
                                    else -> LibraryScreen(
                                        history, state, player,
                                        onQueue = { openPage(Page.QUEUE) }, onLiked = { openPage(Page.LIKED) },
                                        onTogether = { openPage(Page.TOGETHER) }, onAccount = { openPage(Page.SETTINGS) },
                                        onLocalLibrary = if (localLibrary != null) ({ openPage(Page.LOCAL_LIBRARY) }) else null,
                                    )
                                }
                            }
                            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.animation.AnimatedContent(collapsed && state.current != null,
                                    transitionSpec = { if (reduceMotion) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                                        else fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "chrome") { compact ->
                                    if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val current = if (searching) Tab("Search", NuvxIcons.Search) else Tabs[tab]
                                        GlassIconButton(current.icon, "Show tabs, ${current.label} selected", size = 56.dp) { collapsed = false }
                                        MiniPlayerPill(state, player::togglePlay, player::next, { sheet = true }, Modifier.weight(1f), onPrevious = player::previous)
                                        GlassIconButton(NuvxIcons.Search, "Search", size = 56.dp) { openSearch(null) }
                                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MiniPlayerPill(state, player::togglePlay, player::next, { sheet = true }, onPrevious = player::previous)
                                        TabBar(Tabs, tab, searching, select, onSearch = { openSearch(null) })
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(sheet && state.current != null,
                        enter = if (reduceMotion) fadeIn() else slideInVertically(tween(320)) { it },
                        exit = if (reduceMotion) fadeOut() else slideOutVertically(tween(260)) { it }) {
                        NowPlayingSheet(player, state, lyrics, history, router, reduceMotion, onOpen = { sheet = false; open(it) }) { sheet = false }
                    }
                }
            }
        }
    }
}

@Composable private fun SubPage(page: Page, onBack: () -> Unit, body: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass().clickable(onClick = onBack).semantics { contentDescription = "Back"; role = Role.Button }, contentAlignment = Alignment.Center) {
            Icon(NuvxIcons.Chevron, null, Modifier.size(20.dp).graphicsLayerMirror(), tint = NuvxColors.Text)
        }
    }
    LargeTitle(page.title, Modifier.padding(bottom = 8.dp))
    Box(Modifier.weight(1f)) { body() }
}

private fun Modifier.graphicsLayerMirror() = this.then(Modifier.scale(-1f, 1f))

@Composable private fun Welcome(onDone: () -> Unit) = Column(Modifier.safeDrawingPadding().fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
    Image(NuvxMark, "nuvx — bxane's mark", Modifier.size(96.dp), contentScale = ContentScale.Fit)
    Spacer(Modifier.height(28.dp))
    Text("Your next favourite.", color = NuvxColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    Text("Music, with room to listen.", color = NuvxColors.Accent, fontSize = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
    Text("Search YouTube Music and play available songs as a guest. No Google account is connected. Availability depends on the service and your region.", color = NuvxColors.Secondary, fontSize = 16.sp)
    Box(Modifier.padding(top = 32.dp).clip(CircleShape).background(Color.White).clickable(onClick = onDone).padding(horizontal = 28.dp, vertical = 14.dp)
        .semantics { role = Role.Button }) {
        Text("Start listening", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable private fun QueuePage(state: PlayerState, player: NuvxPlayer) =
    if (state.queue.isEmpty()) Text("Your queue is empty. Play something from Search or Home.", color = NuvxColors.Secondary, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
    else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        items(state.queue.size) { i ->
            TrackRow(state.queue[i], Modifier.reorderable(i, state.queue.lastIndex, player::move), active = i == state.index, trailing = if (i != state.index) ({ PlainIconButton(NuvxIcons.Close, "Remove ${state.queue[i].title} from the queue", 44.dp) { player.remove(i) } }) else null) { player.skipTo(i) }
        }
    }

/** One settings row: icon, label, optional subtitle, and a trailing value, switch or chevron. */
private class SettingRow(
    val icon: androidx.compose.ui.graphics.vector.ImageVector?, val label: String, val subtitle: String? = null,
    val value: String? = null, val toggle: Boolean? = null, val onClick: (() -> Unit)? = null,
)

/** Grouped settings: searchable rows in rounded cards with small-caps section labels, then the About links and a footer. */
@Composable private fun SettingsPage(router: BackendRouter, store: KeyValueStore, onTogether: (() -> Unit)? = null, onLiked: (() -> Unit)? = null, onLocal: (() -> Unit)? = null) {
    val selected by router.selected.collectAsState()
    val fallback by router.fallback.collectAsState()
    val health by router.health.collectAsState()
    val uri = LocalUriHandler.current
    var query by remember { mutableStateOf("") }
    fun status(b: BackendId) = health[b]?.let { "${if (it.ok) "Working" else "Unavailable"} · ${it.latencyMs} ms" } ?: "Not used yet this session"
    val sections: List<Pair<String?, List<SettingRow>>> = listOf(
        null to listOfNotNull(onTogether?.let { SettingRow(NuvxIcons.Together, "Listen together", "Share a code and play the same music, in time", onClick = it) }),
        "STREAMING" to router.available.map { b ->
            SettingRow(null, b.label + if (b == BackendId.INNERTUBEX) " (default)" else "", status(b), value = if (selected == b) "In use" else null,
                onClick = { router.choose(b, store) })
        } + SettingRow(null, "Automatic fallback", "Try the other engine when a request fails", toggle = fallback, onClick = { router.chooseFallback(!fallback, store) }),
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
    val shown = sections.map { (title, rows) -> title to rows.filter { q.isEmpty() || it.label.contains(q, true) || it.subtitle?.contains(q, true) == true } }
        .filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = BottomChromePadding)) {
        Field(query, { query = it }, "Search settings", Modifier.fillMaxWidth().padding(bottom = 8.dp))
        shown.forEach { (title, rows) ->
            title?.let { Text(it, color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)) }
                ?: Spacer(Modifier.height(12.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(NuvxColors.Card)) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.padding(start = if (r.icon != null) 56.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(0.08f)))
                    SettingsRowView(r)
                }
            }
        }
        if (shown.isEmpty()) Text("No settings match \"$q\".", color = NuvxColors.Secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
        Text("nuvx · early development · GPL-3.0-only\nInnerTubeX (GPL-3.0) · eXcor (Apache-2.0) · no Google account connected",
            color = NuvxColors.Secondary, fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
    }
}

@Composable private fun SettingsRowView(r: SettingRow) =
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (r.onClick != null) Modifier.focusRing(RoundedCornerShape(20.dp)).clickable(onClick = r.onClick) else Modifier)
        .semantics(mergeDescendants = true) { if (r.toggle != null) role = Role.Switch else if (r.onClick != null) role = Role.Button }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        r.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = NuvxColors.Text); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(r.label, color = NuvxColors.Text, fontSize = 17.sp)
            r.subtitle?.let { Text(it, color = NuvxColors.Secondary, fontSize = 14.sp) }
        }
        when {
            r.toggle != null -> Switch(r.toggle, onCheckedChange = { r.onClick?.invoke() })
            r.value != null -> Text(r.value, color = NuvxColors.Accent, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            r.onClick != null -> Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp).padding(start = 4.dp), tint = NuvxColors.Tertiary)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = BottomChromePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        items(liked.size) { i -> TrackRow(liked[i], active = state.current?.videoId == liked[i].videoId) { player.playQueue(liked, i) } }
    }
}

@Composable private fun AlbumShell(onBack: () -> Unit, body: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).glass().clickable(onClick = onBack).semantics { contentDescription = "Back"; role = Role.Button }, contentAlignment = Alignment.Center) {
            Icon(NuvxIcons.Chevron, null, Modifier.size(20.dp).graphicsLayerMirror(), tint = NuvxColors.Text)
        }
    }
    Box(Modifier.weight(1f)) { body() }
}
