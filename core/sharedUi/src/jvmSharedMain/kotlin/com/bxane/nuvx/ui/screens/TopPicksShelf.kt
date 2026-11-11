package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.ScrollableState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.ui.AppUi
import com.bxane.nuvx.ui.player.CanvasArtworkPlayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import com.bxane.nuvx.ui.components.libraryGrid
import com.bxane.nuvx.ui.components.LIBRARY_GRID_SPACING
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import com.bxane.nuvx.ui.components.SHELF_CARD_WIDTH
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.CARD_ART_PX
import com.bxane.nuvx.data.model.HEADER_ART_PX
import com.bxane.nuvx.data.model.TopPick
import com.bxane.nuvx.data.model.TopPickKind
import com.bxane.nuvx.data.model.TopPickMood
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.rememberBlurredBackdrop
import com.bxane.nuvx.ui.components.ShelfRow
import com.bxane.nuvx.ui.components.SectionHeaderSkeleton
import com.bxane.nuvx.ui.components.ShimmerBox
import com.bxane.nuvx.ui.components.contextClick
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.ui.components.thumbnailBorder
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val TOP_PICK_RATIO = 0.75f

private const val TOP_PICK_FRACTION = 0.60f

private val TOP_PICK_MAX_WIDTH = 280.dp

private val TopPickShape = RoundedCornerShape(12.dp)

private fun topPickWidth(available: Dp): Dp = minOf(available * TOP_PICK_FRACTION, TOP_PICK_MAX_WIDTH)

@Composable
internal fun TopPicksShelf(
    picks: List<TopPick>,
    listenerName: String?,
    onClick: (TopPick, String) -> Unit,
    onLongPress: ((TopPick) -> Unit)?,
) {
    val rowState = rememberLazyListState()
    val hasClip = remember(picks) { mutableStateMapOf<Int, Boolean>() }
    var owner by remember(picks) { mutableIntStateOf(-1) }
    LaunchedEffect(rowState, hasClip) {
        snapshotFlow { rowState.isScrollInProgress to clipOwner(rowState.layoutInfo, hasClip) }
            .collect { (scrolling, next) -> if (!scrolling) owner = next }
    }
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(stringResource(Res.string.top_picks_for_you))
        BoxWithConstraints {
            val cardWidth = topPickWidth(maxWidth)
            PausedWhileScrolling(rowState) {
                ShelfRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    state = rowState,
                ) {
                    itemsIndexed(picks, key = { index, pick -> "$index:${pick.kind}:${pick.title}" }) { index, pick ->
                        CompositionLocalProvider(
                            LocalClipSlot provides ClipSlot(
                                mayPlay = index == owner,
                                onClipFound = { found -> hasClip[index] = found },
                            ),
                        ) {
                            TopPickCard(
                                pick = pick,
                                listenerName = listenerName,
                                onClick = onClick,
                                onLongPress = onLongPress?.takeIf { pick.kind == TopPickKind.LATEST_RELEASE },
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

fun LazyListScope.topPicksSkeleton() {
    item(key = "skeleton:top-picks") {
        Column(Modifier.padding(bottom = 26.dp)) {
            SectionHeaderSkeleton()
            BoxWithConstraints {
                val cardWidth = topPickWidth(maxWidth)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    userScrollEnabled = false,
                ) {
                    items(2) {
                        ShimmerBox(
                            modifier = Modifier.width(cardWidth).aspectRatio(TOP_PICK_RATIO),
                            shape = TopPickShape,
                        )
                    }
                }
            }
        }
    }
}

private class CardText(val eyebrow: String, val title: String, val subtitle: String)

@Composable
private fun cardText(pick: TopPick, listenerName: String?): CardText {
    val artist = pick.artistName.orEmpty()
    return when (pick.kind) {
        TopPickKind.LATEST_RELEASE -> CardText(
            eyebrow = stringResource(Res.string.top_pick_new_from, artist),
            title = pick.title,
            subtitle = localizeCardSubtitle(pick.subtitle),
        )
        TopPickKind.PERSONAL_STATION -> CardText(
            eyebrow = stringResource(Res.string.top_pick_made_for_you),
            title = listenerName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                ?.let { stringResource(Res.string.top_pick_named_station, it) }
                ?: stringResource(Res.string.top_pick_your_station),
            subtitle = "",
        )
        TopPickKind.MOOD -> {
            val mood = pick.mood ?: TopPickMood.FEEL_GOOD
            CardText(
                eyebrow = stringResource(Res.string.top_pick_mood_for_you),
                title = stringResource(mood.titleRes()),
                subtitle = stringResource(mood.lineRes()),
            )
        }
        TopPickKind.ARTIST_AND_SIMILAR -> CardText(
            eyebrow = stringResource(Res.string.top_pick_featuring, artist),
            title = stringResource(Res.string.top_pick_and_similar, artist),
            subtitle = "",
        )
        TopPickKind.FEATURED_ARTIST -> CardText(
            eyebrow = stringResource(Res.string.top_pick_featuring, artist),
            title = pick.title,
            subtitle = pick.subtitle,
        )
        TopPickKind.NEW_RELEASE_MIX -> CardText(
            eyebrow = stringResource(Res.string.top_pick_made_for_you),
            title = pick.title,
            subtitle = pick.subtitle,
        )
        TopPickKind.DISCOVERY_STATION -> CardText(
            eyebrow = stringResource(Res.string.top_pick_made_for_you),
            title = stringResource(Res.string.top_pick_discovery_station),
            subtitle = "",
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopPickCard(
    pick: TopPick,
    listenerName: String?,
    onClick: (TopPick, String) -> Unit,
    onLongPress: ((TopPick) -> Unit)?,
    modifier: Modifier,
) {
    val text = cardText(pick, listenerName)
    val longPress = onLongPress?.let { { it(pick) } }
    Box(
        modifier = modifier
            .aspectRatio(TOP_PICK_RATIO)
            .clip(TopPickShape)
            .thumbnailBorder(TopPickShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressMenuClickable(onClick = { onClick(pick, text.title) }, onLongClick = longPress)
            .contextClick(longPress),
    ) {
        val eyebrowColor: Color
        val branded: Boolean
        when (pick.kind) {
            TopPickKind.PERSONAL_STATION -> {
                PersonalStationArt(Modifier.fillMaxSize())
                eyebrowColor = Color(0xFFFFD2DA)
                branded = true
            }
            TopPickKind.DISCOVERY_STATION -> {
                DiscoveryStationArt(Modifier.fillMaxSize())
                eyebrowColor = Color(0xFFCDBDFF)
                branded = true
            }
            TopPickKind.MOOD -> {
                val style = (pick.mood ?: TopPickMood.FEEL_GOOD).style()
                MoodArt(pick.mood ?: TopPickMood.FEEL_GOOD, style, Modifier.fillMaxSize())
                eyebrowColor = style.eyebrow
                branded = true
            }
            TopPickKind.ARTIST_AND_SIMILAR -> {
                val palette = collagePalette(pick.artistName.orEmpty())
                ArtistCollageArt(pick.images, palette, Modifier.fillMaxSize())
                eyebrowColor = palette.eyebrow
                branded = true
            }
            TopPickKind.LATEST_RELEASE, TopPickKind.NEW_RELEASE_MIX -> {
                ReleaseArt(
                    pick.images.firstOrNull(),
                    Modifier.fillMaxSize(),
                    motion = pick.takeIf { it.kind == TopPickKind.LATEST_RELEASE }?.let { release ->
                        CardMotionSource(release.browseId ?: release.title) {
                            AppUi.host.releaseMotion(release.title, release.artistName.orEmpty())
                        }
                    },
                )
                eyebrowColor = PhotoEyebrow
                branded = false
            }
            TopPickKind.FEATURED_ARTIST -> {
                AsyncImage(
                    model = pick.images.firstOrNull().croppedAt(HEADER_ART_PX * 3 / 4, HEADER_ART_PX).whenArtworkReady(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                CardMotion(pick.artistMotion(), Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                0.72f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                )
                eyebrowColor = PhotoEyebrow
                branded = false
            }
        }
        if (branded) {
            Icon(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .height(15.dp)
                    .aspectRatio(880f / 360f),
            )
        }
        CardCaption(text, eyebrowColor, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun CardCaption(text: CardText, eyebrowColor: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Text(
            text = text.eyebrow,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = eyebrowColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.subtitle.isNotBlank()) {
            Text(
                text = text.subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val PhotoEyebrow = Color.White.copy(alpha = 0.62f)

@Composable
private fun ReleaseArt(url: String?, modifier: Modifier, motion: CardMotionSource? = null) {
    var covered by remember { mutableFloatStateOf(0f) }
    Box(modifier.background(Color.Black)) {
        AsyncImage(
            model = rememberBlurredBackdrop(url.croppedAt(BACKDROP_SOURCE_PX).whenArtworkReady()),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.42f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.6f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.38f),
                    ),
                ),
        )
        AsyncImage(
            model = url.croppedAt(HEADER_ART_PX).whenArtworkReady(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = 1f - covered
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.78f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
        CardMotion(
            motion,
            Modifier.fillMaxWidth().aspectRatio(1f),
            bottomFade = 0.22f,
            onCover = { covered = it },
        )
    }
}

private class CardMotionSource(val key: Any, val lookup: suspend () -> CanvasArtwork?)

private fun TopPick.artistMotion(): CardMotionSource? {
    val channel = artistBrowseId ?: return null
    val name = artistName ?: return null
    return CardMotionSource(channel) { AppUi.host.artistMotion(channel, name) }
}

@Composable
private fun CardMotion(
    source: CardMotionSource?,
    modifier: Modifier,
    bottomFade: Float = 0f,
    onCover: (Float) -> Unit = {},
) {
    if (source == null || !LocalArtworkImages.current) return
    val clip by produceState<CanvasArtwork?>(null, source.key) { value = source.lookup() }
    val slot = LocalClipSlot.current
    val reportFound by rememberUpdatedState(slot?.onClipFound)
    val found = clip != null
    DisposableEffect(found) {
        reportFound?.invoke(found)
        onDispose { reportFound?.invoke(false) }
    }
    val canvas = clip ?: return
    val motion = LocalArtworkMotion.current
    val mayPlay = slot?.mayPlay != false
    val scrolling = LocalArtworkScrolling.current
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(motion, mayPlay, scrolling) {
        when {
            !mayPlay -> mounted = false
            motion -> if (!mounted) {
                delay(CLIP_START_DELAY_MS)
                snapshotFlow { scrolling() }.first { !it }
                mounted = true
            }
            else -> {
                delay(CLIP_RELEASE_DELAY_MS)
                mounted = false
            }
        }
    }
    if (!mounted) return
    CanvasArtworkPlayer(
        canvas = canvas,
        isPlaying = true,
        modifier = modifier,
        bottomFade = bottomFade,
        onCoverChanged = onCover,
        pausedForTransition = !motion || scrolling(),
    )
}

private const val CLIP_START_DELAY_MS = 400L

private const val CLIP_RELEASE_DELAY_MS = 1_000L

private const val BACKDROP_SOURCE_PX = 160

val LocalArtworkMotion = compositionLocalOf { true }

val LocalArtworkScrolling = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
internal fun PausedWhileScrolling(state: ScrollableState, content: @Composable () -> Unit) {
    val outer = LocalArtworkScrolling.current
    val scrolling = remember(outer, state) { { outer() || state.isScrollInProgress } }
    CompositionLocalProvider(LocalArtworkScrolling provides scrolling, content = content)
}

private class ClipSlot(val mayPlay: Boolean, val onClipFound: (Boolean) -> Unit)

private val LocalClipSlot = compositionLocalOf<ClipSlot?> { null }

private fun clipOwner(layout: LazyListLayoutInfo, hasClip: Map<Int, Boolean>): Int {
    val start = layout.viewportStartOffset
    val end = layout.viewportEndOffset
    return layout.visibleItemsInfo
        .filter { hasClip[it.index] == true && it.size > 0 }
        .maxByOrNull { (minOf(it.offset + it.size, end) - maxOf(it.offset, start)).toFloat() / it.size }
        ?.index ?: -1
}

val LocalArtworkImages = compositionLocalOf { true }

@Composable
private fun String?.whenArtworkReady(): String? = if (LocalArtworkImages.current) this else null

@Composable
internal fun FindYourMoodShelf(onClick: (TopPickMood, String) -> Unit, onShowAll: () -> Unit) {
    val rowState = rememberLazyListState()
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(stringResource(Res.string.find_your_mood), onShowAll = onShowAll)
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            state = rowState,
        ) {
            items(TopPickMood.entries.filter { it.isCoreMood }, key = { it.name }) { mood ->
                MoodTile(mood, onClick, Modifier.width(SHELF_CARD_WIDTH))
            }
        }
    }
}

@Composable
fun MoodGridPage(
    gridState: LazyGridState,
    onClick: (TopPickMood, String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            gridItems(TopPickMood.entries, key = { it.name }) { mood ->
                MoodTile(mood, onClick, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MoodTile(mood: TopPickMood, onClick: (TopPickMood, String) -> Unit, modifier: Modifier) {
    val title = stringResource(mood.titleRes())
    Column(modifier.clickable { onClick(mood, title) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp)),
        ) {
            MoodArt(mood, mood.style(), Modifier.fillMaxSize(), square = true, still = true)
            Icon(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .height(11.dp)
                    .aspectRatio(880f / 360f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private class CollagePalette(val top: Color, val bottom: Color, val eyebrow: Color)

private val CollagePalettes = listOf(
    CollagePalette(Color(0xFF8F3F00), Color(0xFFFF6A0F), Color(0xFFFFC48A)),
    CollagePalette(Color(0xFF2D8A20), Color(0xFF4FD22C), Color(0xFFCBFFA3)),
    CollagePalette(Color(0xFF173E9E), Color(0xFF2F7BFF), Color(0xFFC0D8FF)),
    CollagePalette(Color(0xFF4A1E99), Color(0xFF8A4BFF), Color(0xFFDCC8FF)),
    CollagePalette(Color(0xFF0B6E6E), Color(0xFF14B8A6), Color(0xFFB5FFF3)),
    CollagePalette(Color(0xFF8E1426), Color(0xFFF0364F), Color(0xFFFFC4CC)),
)

private fun collagePalette(name: String): CollagePalette = CollagePalettes[abs(name.hashCode()) % CollagePalettes.size]

@Composable
private fun ArtistCollageArt(
    images: List<String>,
    palette: CollagePalette,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier.background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
    ) {
        val w = maxWidth
        val h = maxHeight
        val slots = when (images.size) {
            0 -> emptyList()
            1 -> listOf(Triple(0.5f, 0.34f, 0.66f))
            2 -> listOf(Triple(0.40f, 0.28f, 0.60f), Triple(0.72f, 0.52f, 0.36f))
            else -> listOf(Triple(0.37f, 0.257f, 0.594f), Triple(0.76f, 0.48f, 0.333f), Triple(0.457f, 0.608f, 0.29f))
        }
        slots.zip(images).forEach { (slot, url) ->
            val (cx, cy, d) = slot
            val diameter = w * d
            val circle = Modifier
                .offset(x = w * cx - diameter / 2, y = h * cy - diameter / 2)
                .size(diameter)
                .clip(CircleShape)
            AsyncImage(
                model = url.croppedAt(if (d > 0.5f) HEADER_ART_PX else CARD_ART_PX).whenArtworkReady(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = circle.background(Color.Black.copy(alpha = 0.18f)),
            )
        }
    }
}

private val GOOGLE_SIZE_SUFFIX = Regex("""=w\d+-h\d+[^/?]*$""")

private fun String?.croppedAt(width: Int, height: Int = width): String? {
    if (this == null) return null
    if ("mzstatic.com/image/thumb/" in this) {
        val side = maxOf(width, height)
        return substringBeforeLast('/') + "/${side}x${side}cc.jpg"
    }
    return if (GOOGLE_SIZE_SUFFIX.containsMatchIn(this)) {
        replace(GOOGLE_SIZE_SUFFIX, "=w$width-h$height-p-l90-rj")
    } else {
        artworkAt(maxOf(width, height))
    }
}
