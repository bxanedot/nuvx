package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.components.trackArtSize
import android.os.Build
import com.bxane.nuvx.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import com.bxane.nuvx.ui.components.liquidGlassRim
import com.bxane.nuvx.ui.components.continuous
import com.bxane.nuvx.ui.components.FadingText
import com.bxane.nuvx.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.bxane.nuvx.data.model.LibraryState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.matchesSearch
import com.bxane.nuvx.data.sortedForLibrary
import com.bxane.nuvx.data.useTrackTags
import com.bxane.nuvx.ui.components.CoverPlaceholder
import com.bxane.nuvx.data.canvas.AppleArtistArt
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.canvas.AppleArtistArtRepository
import com.bxane.nuvx.data.canvas.appleCatalogue
import com.bxane.nuvx.data.canvas.ArtistSignature
import com.bxane.nuvx.data.canvas.ArtistSignatureRepository
import com.bxane.nuvx.data.canvas.SignatureInkTransformation
import com.bxane.nuvx.data.canvas.keyColors
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.canvas.CanvasRepository
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.CARD_ART_PX
import com.bxane.nuvx.data.model.HEADER_ART_PX
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.settings.SongSort
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.model.durationMillis
import com.bxane.nuvx.data.model.playingRowMatcher
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.DownloadedBadge
import com.bxane.nuvx.ui.components.ExplicitSongTitle
import com.bxane.nuvx.ui.components.LIBRARY_GRID_SPACING
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.bxane.nuvx.ui.components.RowMoreButton
import com.bxane.nuvx.ui.components.ROW_DIVIDER_INSET
import com.bxane.nuvx.ui.components.SHELF_CARD_WIDTH
import com.bxane.nuvx.ui.components.SongRow
import com.bxane.nuvx.ui.components.SearchPlayingBars
import com.bxane.nuvx.ui.components.libraryGrid
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.components.detailSkeleton
import com.bxane.nuvx.ui.components.topBarContentPadding
import com.bxane.nuvx.ui.components.trackColumnWidth
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import com.bxane.nuvx.ui.graphics.FADE_CURVE_STOPS
import com.bxane.nuvx.ui.graphics.smootherStep
import com.bxane.nuvx.ui.player.CanvasArtworkPlayer
import com.bxane.nuvx.ui.theme.ArtworkPalette
import com.bxane.nuvx.ui.theme.rememberArtworkPalette
import com.bxane.nuvx.ui.icons.SfIcons
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.rememberCoroutineScope
import com.bxane.nuvx.download.DownloadState
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4
private const val ARTIST_ROW_MAX_ITEMS = 5

private const val ARTIST_PHOTO_RATIO = 0.95f

private const val SEARCH_ITEM_INDEX = 1

internal val HEADER_GUTTER = PAGE_GUTTER + 8.dp

internal val ABOUT_GUTTER = PAGE_GUTTER + 6.dp

internal val ARTIST_CONTENT_GUTTER = PAGE_GUTTER

internal val ARTIST_PLAY_BUTTON = 70.dp

internal const val ARTIST_PLAY_ICON_SCALE = 0.56f
internal val ARTIST_SIDE_BUTTON = 52.dp
internal val ARTIST_ACTION_GAP = 24.dp

internal val ABOUT_LOGO_HEIGHT = 56.dp

internal const val SVG_DECODE_WIDTH = 1600

private val CREDIT_SEPARATOR = Regex(""",\s|\s&\s|\s(?:x|and|feat\.?|ft\.?|with)\s""", RegexOption.IGNORE_CASE)

private fun String.looksLikeCredit() = CREDIT_SEPARATOR.containsMatchIn(this)

@Composable
fun DetailScreen(
    page: DetailPage,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    activeShelf: HomeShelf? = null,
    onActiveShelfChange: (HomeShelf?) -> Unit = {},
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    onToggleLibrary: (() -> Unit)? = null,
    onToggleSubscription: (() -> Unit)? = null,
    onDownloadCatalog: () -> Unit = {},
    catalogLoading: Boolean = false,
    releaseLibrary: Map<String, LibraryState> = emptyMap(),
    onLoadReleaseLibrary: ((String) -> Unit)? = null,
    onToggleReleaseLibrary: ((String) -> Unit)? = null,
    songSort: SongSort = SongSort.DEFAULT,
    folder: LocalFolder? = null,
    reorder: (@Composable (ArtworkPalette) -> Unit)? = null,
) {
    val rawSongs = (page.songs as? UiState.Success)?.data.orEmpty()
    val folderSort = folder?.sort
    val songs = remember(rawSongs, songSort, folderSort) {
        if (folderSort != null) rawSongs.sortedForLibrary(folderSort) else rawSongs.sortedForDetail(songSort)
    }
    val originalTrackNumbers = remember(rawSongs) {
        val byTag = rawSongs.useTrackTags()
        rawSongs.withIndex().associate { (i, s) -> s.videoId to ((if (byTag) s.trackNumber else null) ?: (i + 1)) }
    }
    val isArtist = page.type == BrowseType.ARTIST
    val topReleaseId = if (isArtist) page.sections.topRelease()?.browseId else null
    LaunchedEffect(topReleaseId) { topReleaseId?.let { onLoadReleaseLibrary?.invoke(it) } }
    var appleArt by remember(page.browseId) {
        mutableStateOf(if (isArtist) AppleArtistArtRepository.cached(page.browseId) else null)
    }
    val songsLoading = page.songs is UiState.Loading
    LaunchedEffect(page.browseId, page.title, songsLoading) {
        if (isArtist && !songsLoading) {
            appleArt = AppleArtistArtRepository.artFor(page.browseId, page.title) { page.appleCatalogue() } ?: appleArt
        }
    }
    val signatureEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureBaseUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val signatureUpdates by ArtistSignatureRepository.updates.collectAsStateWithLifecycle()
    var signature by remember(page.browseId) { mutableStateOf<ArtistSignature?>(null) }
    val nameSettled = !page.title.looksLikeCredit() || page.songs !is UiState.Loading
    LaunchedEffect(page.browseId, page.title, nameSettled, signatureEnabled, signatureBaseUrl, signatureUpdates) {
        if (!isArtist || !nameSettled) return@LaunchedEffect
        val base = ArtistSignatureRepository.effectiveBaseUrl(signatureEnabled, signatureBaseUrl)
        if (base == null) {
            signature = null
            return@LaunchedEffect
        }
        val backoff = listOf(0L, 5_000L, 15_000L, 30_000L)
        for (wait in backoff) {
            if (wait > 0) delay(wait)
            signature = ArtistSignatureRepository.cached(page.title, base)
                ?: ArtistSignatureRepository.signatureFor(page.title, base)
                ?: signature
            if (signature != null) return@LaunchedEffect
        }
    }
    val palette = rememberArtworkPalette(
        imageUrl = appleArt?.heroUrl ?: page.thumbnailUrl,
        keyColors = appleArt?.keyColors(),
    )

    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    var focusSearch by remember(page.browseId) { mutableStateOf(false) }
    val closeSearch = { onSearchingChange(false) }
    BackHandler(enabled = searching) { closeSearch() }

    val matches = remember(songs, query) { songs.matching(query) }
    val queue = remember(matches) { matches.map { it.value } }
    val suggested = remember(page.suggestedSongs, query) {
        page.suggestedSongs.matching(query).map { it.value }
    }

    val folderState = folder?.state
    val folderGroups = if (folder != null && folderState != null) {
        rememberLocalFolderGroups(page.browseId, folder, folderState.tab, rawSongs, query)
    } else {
        emptyList()
    }
    BackHandler(enabled = folderState?.selecting == true) { folderState?.clear() }

    val downloadedTint = palette.accent.takeUnless { page.browseId.startsWith("local:") }

    val collections by Downloads.collections.collectAsStateWithLifecycle()
    val coverGrid = remember(page.browseId, songs, collections) {
        if (page.browseId != "local:downloads") emptyList()
        else downloadsCoverGrid(
            covers = songs.mapNotNull { it.thumbnailUrl },
            artistPhotos = collections.values.filter { it.artist }.mapNotNull { it.thumbnailUrl },
        )
    }

    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedDetailArtwork.collectAsStateWithLifecycle()
    val prioritizeSpotifyCanvas by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    val credit = page.headerLines(songs.size).first.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, canvasEnabled, prioritizeSpotifyCanvas) {
        if (isArtist) return@LaunchedEffect
        if (!canvasEnabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }
    val artistVideo = appleArt?.videoUrl
    LaunchedEffect(artistVideo, canvasEnabled) {
        if (isArtist) canvas = artistVideo?.takeIf { canvasEnabled }?.let { CanvasArtwork(url = it) }
    }

    val searchStop = with(LocalDensity.current) { topBarContentPadding().roundToPx() }
    LaunchedEffect(searching) {
        if (searching) {
            focusSearch = true
            listState.animateScrollToItem(SEARCH_ITEM_INDEX, -searchStop)
        } else {
            query = ""
        }
    }

    AnimatedContent(
        targetState = activeShelf,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        label = "artist_shelf_transition",
        modifier = modifier.fillMaxSize(),
    ) { targetShelf ->
        if (targetShelf == null) {
            val pageColor = if (isArtist) palette.wash else palette.background
            BoxWithConstraints(Modifier.fillMaxSize().background(pageColor)) {
                val artHeight = (maxWidth / ARTIST_PHOTO_RATIO).coerceAtMost(maxHeight * 0.6f)
                val sleeveSize = (maxWidth * SLEEVE_FRACTION).coerceAtMost(maxHeight * SLEEVE_MAX_HEIGHT)
                val fullBleedSleeve = fullBleedArtwork && maxWidth <= maxHeight * FULL_BLEED_MAX_HEIGHT
                val gridColumns = libraryGrid(maxWidth - PAGE_GUTTER * 2).columns

                if (isArtist) {
                    PageBackground(
                        page = page,
                        palette = palette,
                        canvas = canvas,
                        artHeight = artHeight,
                        listState = listState,
                        heroUrl = appleArt?.heroUrl,
                        modifier = Modifier.matchParentSize(),
                    )
                }

                if (reorder != null) {
                    reorder(palette)
                } else LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(page = page, palette = palette, artHeight = artHeight, appleArt = appleArt, signature = signature)
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        sleeveSize = sleeveSize,
                        fullBleed = fullBleedSleeve,
                        canvas = canvas,
                        songs = songs,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        onArtistClick = onArtistClick.takeIf { folder == null },
                        onToggleLibrary = onToggleLibrary,
                        onDownloadAll = folder?.onDownload?.let { download -> { download(songs) } },
                        coverGrid = coverGrid,
                    )
                }
            }

            if (searching) {
                item(key = "search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = closeSearch,
                        autoFocus = focusSearch,
                        onFocused = { focusSearch = false },
                        palette = palette,
                        type = page.type,
                    )
                }
            }

            val description = page.description

            if (songs.isNotEmpty() && isArtist) {
                item(key = "actions") {
                    ActionRow(
                        onPlay = { onSongClick(songs, 0) },
                        artistId = page.browseId,
                        onDownloadCatalog = onDownloadCatalog,
                        catalogLoading = catalogLoading,
                        subscription = page.subscription?.takeIf { onToggleSubscription != null },
                        onToggleSubscription = onToggleSubscription,
                        bottomSpace = 22.dp,
                        playColor = appleArt?.keyColor?.let { Color(it) },
                    )
                }
            }

            val topRelease = if (isArtist) page.sections.topRelease() else null
            if (topRelease != null) {
                item(key = "top-release") {
                    TopReleaseCard(
                        item = topRelease,
                        palette = palette,
                        onClick = { onSectionItemClick(topRelease) },
                        onLongPress = onSectionItemLongPress?.let { { it(topRelease) } },
                        saved = topRelease.browseId?.let { releaseLibrary[it]?.saved },
                        onToggleSaved = onToggleReleaseLibrary?.let { toggle ->
                            topRelease.browseId?.let { id -> { toggle(id) } }
                        },
                    )
                }
            }

            val hasStats = isArtist &&
                (page.subscriberCountText != null || page.monthlyListenerCount != null)
            val aboutSignature = if (isArtist && appleArt?.logoUrl != null) signature else null
            val showAbout = (!description.isNullOrBlank() &&
                (page.type == BrowseType.ALBUM || isArtist)) || hasStats || aboutSignature != null
            fun LazyListScope.aboutItem() {
                if (!showAbout) return
                item(key = "about") {
                    Box(Modifier.padding(top = if (isArtist) 28.dp else 0.dp)) {
                    AboutSection(
                        title = stringResource(
                            if (isArtist) R.string.about_artist else R.string.about_album,
                        ),
                        text = description?.takeIf { it.isNotBlank() },
                        palette = palette,
                        horizontalPadding = if (isArtist) ARTIST_CONTENT_GUTTER else ABOUT_GUTTER,
                        stats = if (hasStats) {
                            {
                                ArtistStatsRow(
                                    subscriberCountText = page.subscriberCountText,
                                    monthlyListenerCount = page.monthlyListenerCount,
                                    palette = palette,
                                )
                            }
                        } else {
                            null
                        },
                        footer = aboutSignature?.let { sig ->
                            {
                                AboutSignature(
                                    signature = sig,
                                    name = page.title,
                                    palette = palette,
                                    horizontalPadding = ARTIST_CONTENT_GUTTER,
                                )
                            }
                        },
                    )
                    }
                }
            }
            if (!isArtist) aboutItem()

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (folder != null && folderState != null) {
                    localFolderItems(
                        folder = folder,
                        state = folderState,
                        songs = queue,
                        groups = folderGroups,
                        query = query,
                        columns = gridColumns,
                        palette = palette,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onSongClick = onSongClick,
                        onSongLongPress = onSongLongPress,
                        onSongSwipe = onSongSwipe,
                    )
                } else if (isArtist) {
                    item {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        val isCurrentRow = playingRowMatcher(top, currentSong)
                        SectionHeading(
                            title = stringResource(R.string.top_songs),
                            palette = palette,
                            horizontalPadding = ARTIST_CONTENT_GUTTER,
                        )
                        BoxWithConstraints {
                            val columnWidth = trackColumnWidth(maxWidth)
                            val topSongsState = rememberLazyListState()
                            LazyRow(
                                state = topSongsState,
                                contentPadding = PaddingValues(horizontal = ARTIST_CONTENT_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                flingBehavior = rememberSnapFlingBehavior(lazyListState = topSongsState),
                            ) {
                                items(top.chunked(SONGS_PER_COLUMN)) { column ->
                                    Column(Modifier.width(columnWidth)) {
                                        column.forEach { song ->
                                            CompactSongRow(
                                                song = song,
                                                palette = palette,
                                                onClick = { onSongClick(top, top.indexOf(song)) },
                                                onLongPress = { onSongLongPress(song) },
                                                downloadedTint = downloadedTint,
                                                isCurrent = isCurrentRow(song),
                                                isPlaying = isCurrentRow(song) && isPlaying,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val numbered = page.type == BrowseType.ALBUM
                    val isCurrentRow = playingRowMatcher(matches.map { it.value }, currentSong)
                    if (matches.isEmpty() && state.data.isNotEmpty()) {
                        item(key = "no-matches") {
                            MessageState(stringResource(R.string.nothing_matches, query))
                        }
                    }
                    itemsIndexed(
                        items = matches,
                        key = { _, entry -> "${entry.index}_${entry.value.videoId}" },
                    ) { position, entry ->
                        val song = entry.value
                        val isCurrent = isCurrentRow(song)
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = {
                                onSongClick(queue, position)
                            },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = Color.Transparent,
                            trackNumber = originalTrackNumbers[song.videoId].takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                            downloadedTint = downloadedTint,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            activeTint = palette.accent,
                            searchPlayingStyle = true,
                        )
                        if (position < matches.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = if (numbered) NUMBERED_ROW_DIVIDER_INSET else ROW_DIVIDER_INSET,
                                ),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            if (suggested.isNotEmpty()) {
                item(key = "suggested-heading") {
                    SectionHeading(stringResource(R.string.suggested), palette)
                }
                val isSuggestedCurrent = playingRowMatcher(suggested, currentSong)
                itemsIndexed(
                    suggested,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
                        palette = palette,
                        onClick = { onSongClick(suggested, index) },
                        onLongPress = { onSongLongPress(song) },
                        onAdd = { onAddSuggested(song) },
                        downloadedTint = downloadedTint,
                        isCurrent = isSuggestedCurrent(song),
                        isPlaying = isSuggestedCurrent(song) && isPlaying,
                    )
                    if (index < suggested.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                }
            }

            items(page.sections) { shelf ->
                val canShowAll = shelf.items.size > ARTIST_ROW_MAX_ITEMS
                val displayItems = remember(shelf.items) {
                    if (canShowAll) shelf.items.take(ARTIST_ROW_MAX_ITEMS) else shelf.items
                }
                Column(Modifier.padding(top = 22.dp)) {
                    SectionHeading(
                        title = shelf.title,
                        palette = palette,
                        onShowAll = if (canShowAll) { { onActiveShelfChange(shelf) } } else null,
                        horizontalPadding = ARTIST_CONTENT_GUTTER,
                    )
                    val carouselState = rememberLazyListState()
                    LazyRow(
                        state = carouselState,
                        contentPadding = PaddingValues(horizontal = ARTIST_CONTENT_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        flingBehavior = rememberSnapFlingBehavior(lazyListState = carouselState),
                    ) {
                        items(displayItems) { item ->
                            SectionCard(
                                item = item,
                                palette = palette,
                                onClick = { onSectionItemClick(item) },
                                onLongPress = onSectionItemLongPress?.let { { it(item) } },
                                modifier = Modifier.width(if (item.isVideo) VIDEO_CARD_WIDTH else SHELF_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
            if (isArtist) aboutItem()
        }

                if (folder != null && folderState != null && folderState.selecting) {
                    LocalFolderSelectionBar(
                        folder = folder,
                        state = folderState,
                        songs = songs,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topBarContentPadding(), start = PAGE_GUTTER, end = PAGE_GUTTER),
                    )
                }

    }
} else {
    ArtistShelfGridPage(
        shelf = targetShelf,
        palette = palette,
        onItemClick = onSectionItemClick,
        onItemLongPress = onSectionItemLongPress,
        contentPadding = contentPadding,
    )
}
}
}

internal fun List<Song>.sortedForDetail(sort: SongSort): List<Song> = when (sort) {
    SongSort.DEFAULT -> this
    SongSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.DATE_ADDED_ASC -> withIndex()
        .sortedWith(
            compareBy<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MAX_VALUE
            }.thenBy { (position, _) -> position },
        )
        .map { it.value }
    SongSort.DATE_ADDED_DESC -> withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MIN_VALUE
            }.thenByDescending { (position, _) -> position },
        )
        .map { it.value }
}

internal fun List<Song>.matching(query: String): List<IndexedValue<Song>> {
    val all = withIndex().toList()
    if (query.isBlank()) return all
    return all.filter { (_, song) -> song.matchesSearch(query) }
}

private val downloadsCoverSeed = Random.nextInt()

internal fun downloadsCoverGrid(
    covers: List<String>,
    artistPhotos: List<String>,
    seed: Int = downloadsCoverSeed,
): List<String> {
    val random = Random(seed)
    val artists = artistPhotos.distinct().takeIf { it.size >= 2 }?.shuffled(random)?.take(2).orEmpty()
    val grid = (artists + covers.distinct().shuffled(random)).distinct().take(4)
    return if (grid.size == 4) grid.shuffled(random) else emptyList()
}

private fun List<HomeShelf>.topRelease(): ShelfItem? =
    asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.browseId?.startsWith("MPRE") == true }
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<ShelfItem>> { it.value.releaseYear() ?: 0 }
                .thenByDescending { it.index },
        )?.value

private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")

private fun ShelfItem.releaseYear(): Int? =
    RELEASE_YEAR.find(subtitle)?.value?.toIntOrNull()

@androidx.annotation.StringRes
private fun ShelfItem.recentLabel(): Int {
    val kind = subtitle.lowercase()
    return when {
        kind.contains("single") -> R.string.recent_single
        Regex("""\bep\b""").containsMatchIn(kind) -> R.string.recent_ep
        else -> R.string.recent_album
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopReleaseCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    saved: Boolean?,
    onToggleSaved: (() -> Unit)?,
) {
    val shape = remember { RoundedCornerShape(TOP_RELEASE_CORNER).continuous() }
    val coverShape = remember { RoundedCornerShape(TOP_RELEASE_CORNER - TOP_RELEASE_INSET).continuous() }
    val haptics = rememberHaptics()
    val hasButton = onToggleSaved != null && saved != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ARTIST_CONTENT_GUTTER)
            .padding(bottom = 22.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), shape)
            .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), shape)
            .clip(shape)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(
                start = TOP_RELEASE_INSET,
                top = TOP_RELEASE_INSET,
                bottom = TOP_RELEASE_INSET,
                end = if (hasButton) 19.dp else TOP_RELEASE_INSET,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(coverShape)
                .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_COVER_EDGE_ALPHA), coverShape)
                .background(palette.elevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val secondary = palette.onBackground.copy(alpha = TOP_RELEASE_SECONDARY_ALPHA)
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FadingText(
                text = item.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    lineHeight = TOP_RELEASE_TITLE_LINE_HEIGHT,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
                color = palette.onBackground,
                maxLines = 2,
            )
            Text(
                text = stringResource(item.recentLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
            )
        }
        if (onToggleSaved != null && saved != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
                    .clickable {
                        haptics.play(if (saved) Haptic.ToggleOff else Haptic.ToggleOn)
                        onToggleSaved()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) SfIcons.Check else SfIcons.Add,
                    contentDescription = stringResource(
                        if (saved) R.string.remove_from_library else R.string.add_to_library,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

private val TOP_RELEASE_CORNER = 24.dp
private val TOP_RELEASE_INSET = 12.dp

private const val TOP_RELEASE_SECONDARY_ALPHA = 0.55f

private val TOP_RELEASE_TITLE_LINE_HEIGHT = 22.sp

internal const val LIGHT_FILL_ALPHA = 0.07f
internal const val DISABLED_ACTION_ALPHA = 0.38f

internal const val TOP_RELEASE_EDGE_ALPHA = 0.10f
private const val TOP_RELEASE_COVER_EDGE_ALPHA = 0.215f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    onLongPress: (() -> Unit)? = null,
) {
    if (item.isVideo) {
        VideoShelfCard(
            item = item,
            onClick = onClick,
            onLongPress = onLongPress,
            modifier = modifier,
            titleColor = palette.onBackground,
            subtitleColor = palette.onBackgroundVariant,
            placeholderColor = palette.elevated,
        )
        return
    }
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .thumbnailBorder(RoundedCornerShape(10.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistShelfGridPage(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = PaddingValues(
                top = topBarContentPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAGE_GUTTER),
        ) {
            items(shelf.items, key = { it.browseId ?: it.videoId ?: it.title }) { item ->
                SectionCard(
                    item = item,
                    palette = palette,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
