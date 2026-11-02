package com.bxane.nuvx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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

private val Tabs = listOf(Tab("Home", NuvxIcons.Home), Tab("Explore", NuvxIcons.Explore), Tab("Library", NuvxIcons.Library))
private enum class Page(val title: String) { TOGETHER("Listen Together"), SETTINGS("Settings"), QUEUE("Queue") }

@Composable
fun NuvxApp(
    router: BackendRouter, store: KeyValueStore, player: NuvxPlayer, lyrics: LrcLib, watch: Boolean, reduceMotion: Boolean = false,
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
    // Back unwinds one layer at a time: player sheet, sub-page, search, then a non-Home tab returns to Home.
    val canGoBack = welcomed && !watch && (sheet || page != null || searching || tab != 0)
    onBackHandler?.invoke(canGoBack) {
        when {
            sheet -> sheet = false
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
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    val select = { i: Int -> tab = i; searching = false; page = null }
                    val openSearch = { q: String? -> searchQuery = q; searching = true; page = null }
                    val openPage = { p: Page -> page = p }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().safeDrawingPadding()) {
                            val content = Modifier.align(Alignment.TopCenter).fillMaxSize().then(if (wide) Modifier.widthIn(max = 1100.dp) else Modifier)
                            Box(content) {
                                when {
                                    page != null -> SubPage(page!!, { page = null }) {
                                        when (page!!) {
                                            Page.TOGETHER -> TogetherPage(together, store)
                                            Page.SETTINGS -> SettingsPage(router, store)
                                            Page.QUEUE -> QueuePage(state, player)
                                        }
                                    }
                                    searching -> SearchScreen(router, player, history, searchQuery) { openPage(Page.SETTINGS) }
                                    tab == 0 -> HomeScreen(history, router, player, { openPage(Page.TOGETHER) }) { openPage(Page.SETTINGS) }
                                    tab == 1 -> ExploreScreen({ openSearch(it) }) { openPage(Page.SETTINGS) }
                                    else -> LibraryScreen(history, state, player, { openPage(Page.QUEUE) }, { openPage(Page.TOGETHER) }) { openPage(Page.SETTINGS) }
                                }
                            }
                            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                MiniPlayerPill(state, player::togglePlay, player::next, { sheet = true })
                                TabBar(Tabs, tab, searching, select, onSearch = { openSearch(null) })
                            }
                        }
                    }
                    AnimatedVisibility(sheet && state.current != null,
                        enter = if (reduceMotion) fadeIn() else slideInVertically(tween(320)) { it },
                        exit = if (reduceMotion) fadeOut() else slideOutVertically(tween(260)) { it }) {
                        NowPlayingSheet(player, state, lyrics, reduceMotion) { sheet = false }
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
    Image(NuvxMark, "nuvx — bxane's lightning mark", Modifier.size(96.dp), contentScale = ContentScale.Fit)
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
        items(state.queue.size) { i -> TrackRow(state.queue[i], active = i == state.index) { player.skipTo(i) } }
    }

@Composable private fun SettingsPage(router: BackendRouter, store: KeyValueStore) {
    val selected by router.selected.collectAsState()
    val fallback by router.fallback.collectAsState()
    val health by router.health.collectAsState()
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = BottomChromePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("STREAMING ENGINE", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(NuvxColors.Card)) {
            router.available.forEach { backend ->
                Row(Modifier.fillMaxWidth().clickable { router.choose(backend, store) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(backend.label + if (backend == BackendId.INNERTUBEX) " · Default" else " · Alternative", color = NuvxColors.Text, fontSize = 17.sp)
                        Text(health[backend]?.let { "${it.operation}: ${if (it.ok) "available" else "unavailable"} · ${it.latencyMs} ms" } ?: "Not checked this session", color = NuvxColors.Secondary, fontSize = 13.sp)
                    }
                    RadioButton(selected == backend, onClick = { router.choose(backend, store) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic fallback", color = NuvxColors.Text, fontSize = 17.sp)
                    Text("Try the other engine when a request fails.", color = NuvxColors.Secondary, fontSize = 13.sp)
                }
                Switch(fallback, onCheckedChange = { router.chooseFallback(it, store) })
            }
        }
        Text("The selected engine applies to new requests. eXcor streams may be refused by the service; fallback can use InnerTubeX.", color = NuvxColors.Secondary, fontSize = 13.sp)
        Text("ABOUT", color = NuvxColors.Secondary, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(NuvxMark, null, Modifier.size(56.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text("nuvx", color = NuvxColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Developed and led by bxane. Brand icons supplied by bxane.", color = NuvxColors.Secondary, fontSize = 14.sp)
            }
        }
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(NuvxColors.Card)) {
            listOf("Official nuvx website" to "https://nuvx.lol", "Developer portfolio" to "https://bxane.lol",
                "Alternative developer portfolio" to "https://guns.lol/bxane", "Community Discord" to "https://discord.gg/h4Wsu824Uf",
                "Contact bxane" to "mailto:bxanee@gmail.com", "Source code and licences" to "https://github.com/bxanedot/nuvx").forEach { (label, url) ->
                Row(Modifier.fillMaxWidth().clickable { uri.openUri(url) }.padding(16.dp).semantics { role = Role.Button }, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = NuvxColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Icon(NuvxIcons.Chevron, null, Modifier.size(16.dp), tint = NuvxColors.Tertiary)
                }
            }
        }
        Text("Early development · GPL-3.0-only\nInnerTubeX · GPL-3.0\neXcor · Apache-2.0\nNo Google account connected.", color = NuvxColors.Secondary, fontSize = 13.sp)
    }
}

@Composable private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, ime: ImeAction = ImeAction.Done, onIme: () -> Unit = {}) =
    Box(modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(NuvxColors.Card).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = NuvxColors.Tertiary, fontSize = 17.sp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = placeholder }, singleLine = true,
            textStyle = TextStyle(color = NuvxColors.Text, fontSize = 17.sp), cursorBrush = SolidColor(NuvxColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ime), keyboardActions = KeyboardActions(onAny = { onIme() }))
    }

@Composable private fun PillButton(text: String, enabled: Boolean, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.height(52.dp).clip(CircleShape).background(if (primary) Color.White.copy(if (enabled) 1f else 0.4f) else NuvxColors.Card)
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
