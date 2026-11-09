package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.model.Song
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.PullToRefresh
import com.bxane.nuvx.ui.components.ShimmerBox
import com.bxane.nuvx.ui.components.feedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    /** YouTube Music's own Explore shelves; the first leads as large editorial cards, as Convx has it. */
    shelves: List<HomeShelf> = emptyList(),
    onShelfItemClick: (ShelfItem, String) -> Unit = { _, _ -> },
    onShelfItemLongPress: ((ShelfItem) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            // 14dp under the title, the same gap Library leaves
                            // above its first card (8dp title + 6dp Replay row).
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        val pageWidth = maxWidth
                        shelves.firstOrNull()?.let { lead ->
                            item(key = "explore:hero") {
                                EditorialRow(lead, pageWidth, { onShelfItemClick(it, lead.title) }, onShelfItemLongPress)
                            }
                            val rest = lead.items.drop(HERO_COUNT)
                            if (rest.isNotEmpty()) item(key = "explore:lead") {
                                Shelf(
                                    shelf = lead.copy(items = rest),
                                    onItemClick = { onShelfItemClick(it, lead.title) },
                                    onItemLongPress = onShelfItemLongPress,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                            }
                        }
                        shelves.drop(1).forEachIndexed { index, shelf ->
                            item(key = "explore:shelf:$index") {
                                Shelf(
                                    shelf = shelf,
                                    onItemClick = { onShelfItemClick(it, shelf.title) },
                                    onItemLongPress = onShelfItemLongPress,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                            }
                        }
                        // Moods and genres: a row of Search's colour tiles per section.
                        state.data.filter { it.items.isNotEmpty() }.forEachIndexed { index, section ->
                            item(key = "explore:moods:$index") {
                                Column(Modifier.padding(bottom = 26.dp)) {
                                    SectionHeader(section.title)
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                                    ) {
                                        items(section.items.distinctBy { it.browseId to it.params }, key = { "${it.browseId}|${it.params}" }) { mood ->
                                            CategoryTile(mood, Modifier.width(MOOD_TILE_WIDTH)) { onCategoryClick(mood) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Convx's lead: wide cards, a line of type over each picture, most of a screen across. */
@Composable
private fun EditorialRow(
    shelf: HomeShelf,
    pageWidth: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)?,
) {
    val width = (pageWidth * 0.88f).coerceAtMost(360.dp)
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 26.dp),
    ) {
        items(shelf.items.take(HERO_COUNT)) { item ->
            Column(
                Modifier
                    .width(width)
                    .longPressMenuClickable(onClick = { onClick(item) }, onLongClick = onLongPress?.let { { it(item) } }),
            ) {
                Text(
                    text = localizeShelfTitle(shelf.title).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = localizeCardSubtitle(item.subtitle),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(HERO_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

/** How many of the lead shelf's items get a large card; the rest follow as a normal row. */
private const val HERO_COUNT = 6

private const val HERO_ART_PX = 1080

private val MOOD_TILE_WIDTH = 180.dp

/**
 * A solid stripe down the left and the category's cover filling the rest,
 * redrawn as a duotone of the stripe's colour so every card reads as one
 * tinted sleeve rather than a photo pasted onto a swatch.
 *
 * Only the artwork: the caller sizes, clips and makes it clickable, which is
 * how the TV app wraps the same sleeve in its own focus treatment.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }
    Box(modifier = modifier.background(tone.stripe)) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(1f - MOOD_STRIPE_FRACTION)
                .background(tone.placeholder),
        ) {
            item.thumbnailUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = tone.duotone,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Pale stripes (Energize's cream, Feel good's mint) would swallow
        // white type; a soft shade under the title keeps it legible.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .24f), .62f to Color.Transparent)),
        )
        Text(
            text = item.title,
            style = titleStyle.copy(
                shadow = Shadow(Color.Black.copy(alpha = .35f), offset = Offset(0f, 1f), blurRadius = 6f),
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private class MoodTone(val stripe: Color, val placeholder: Color, val duotone: ColorFilter)

private fun moodTone(item: MoodGenre): MoodTone {
    val base = item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)
    // YouTube's palette is already pastel; a touch of black keeps it from
    // glowing against the black page.
    val stripe = lerp(base, Color.Black, .08f)
    val shadow = lerp(base, Color.Black, .68f)
    val highlight = lerp(base, Color.White, .32f)
    return MoodTone(
        stripe = stripe,
        placeholder = lerp(shadow, highlight, .45f),
        duotone = duotone(shadow, highlight),
    )
}

/**
 * Maps each pixel's luminance onto the ramp [shadow] to [highlight]. Offsets
 * are in 0..255, which Compose honours on Skia too (it rescales them).
 */
private fun duotone(shadow: Color, highlight: Color): ColorFilter {
    fun channel(from: Float, to: Float): FloatArray {
        val span = to - from
        return floatArrayOf(span * .299f, span * .587f, span * .114f, 0f, from * 255f)
    }
    return ColorFilter.colorMatrix(
        ColorMatrix(
            channel(shadow.red, highlight.red) +
                channel(shadow.green, highlight.green) +
                channel(shadow.blue, highlight.blue) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ),
    )
}

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(18.dp)

/** Width over height; a two-column phone row lands at about 100dp tall. */
const val MOOD_CARD_ASPECT = 1.72f

/** How much of the card the solid stripe covers before the artwork starts. */
private const val MOOD_STRIPE_FRACTION = .28f

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
