package com.bxane.nuvx.ui.replay

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.stats.ArtistFacts
import com.bxane.nuvx.data.stats.ReplayPeriod
import com.bxane.nuvx.data.stats.ReplaySummary
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.player.MeshGradientBackground
import com.bxane.nuvx.ui.player.rememberArtworkColors
import com.bxane.nuvx.ui.theme.AccentRed

@Composable
private fun isDarkMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun ReplayScreen(
    state: ReplayState,
    holder: String,
    onPeriodChange: (ReplayPeriod) -> Unit,
    onOpenStory: (ReplayStoryPage) -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenArtist: (String?, String) -> Unit,
    onOpenAlbum: (String?, String, String?, String?) -> Unit,
    onShare: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    landingPage: ReplayStoryPage = ReplayStoryPage.INTRO,
) {
    val context = LocalContext.current
    val summary = state.summary
    val leadArtwork = summary?.songs?.firstOrNull()?.song?.thumbnailUrl
    val palette = rememberArtworkColors(leadArtwork)
    val topSongs = stringResource(R.string.top_songs)
    val topArtists = stringResource(R.string.top_artists)
    val topAlbums = stringResource(R.string.top_albums)
    val topGenres = stringResource(R.string.top_genres)

    var landed by rememberSaveable(landingPage, state.period) { mutableStateOf(false) }
    LaunchedEffect(landingPage, state.period, summary == null) {
        if (landed) return@LaunchedEffect
        val target = when {
            landingPage == ReplayStoryPage.INTRO || landingPage == ReplayStoryPage.MINUTES -> 0
            summary == null || summary.isEmpty -> 0
            else -> replayChartIndex(summary, landingPage)
        }
        listState.scrollToItem(target)
        if (summary != null) landed = true
    }

    Box(modifier.fillMaxSize()) {
        MeshGradientBackground(palette = palette, trackKey = leadArtwork, animated = false)
        val dark = isDarkMode()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.88f),
                        ) else listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.65f),
                            Color.White.copy(alpha = 0.82f),
                        ),
                    ),
                ),
        )

        val textColor = if (dark) Color.White else Color.Black
        val textVariant = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("heading") { Heading(state, onPeriodChange, dark) }

            when {
                state.loading && summary == null -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(64.dp), Alignment.Center) {
                        CircularProgressIndicator(color = textColor.copy(alpha = 0.6f))
                    }
                }
                summary == null || summary.isEmpty -> item("empty") { EmptyReplay(state.period, dark) }
                else -> {
                    item("cards") {
                        ReplayCardRow(
                            cards = summary.cards(context),
                            holder = holder,
                            memberSince = state.memberSince,
                            onCardClick = onOpenStory,
                        )
                    }
                    item("open") {
                        ReplayActionRow(SfIcons.PlayArrow, stringResource(R.string.play_your_replay), dark) {
                            onOpenStory(ReplayStoryPage.INTRO)
                        }
                    }

                    chart(
                        key = "songs",
                        title = topSongs,
                        rows = summary.songRows(CHART_LENGTH),
                        dark = dark,
                        onClick = { index ->
                            summary.songs.getOrNull(index)?.let { onPlaySong(it.song) }
                        },
                    )
                    chart(
                        key = "artists",
                        title = topArtists,
                        rows = summary.artistRows(CHART_LENGTH),
                        dark = dark,
                        circular = true,
                        onClick = { index ->
                            val artist = summary.artists.getOrNull(index) ?: return@chart
                            onOpenArtist(artist.browseId, artist.title)
                        },
                    )
                    chart(
                        key = "albums",
                        title = topAlbums,
                        rows = summary.albumRows(CHART_LENGTH),
                        dark = dark,
                        onClick = { index ->
                            val album = summary.albums.getOrNull(index) ?: return@chart
                            onOpenAlbum(album.browseId, album.title, album.subtitle, album.artworkUrl)
                        },
                    )
                    if (summary.genres.isNotEmpty()) {
                        chart(
                            key = "genres",
                            title = topGenres,
                            rows = summary.genreRows(CHART_LENGTH),
                            dark = dark,
                            onClick = {},
                        )
                    } else if (ArtistFacts.genresAvailable) {
                        item("genres-pending") {
                            Note(
                                text = stringResource(R.string.genres_pending),
                                dark = dark,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp),
                            )
                        }
                    }

                    item("habits") { Habits(summary, dark) }
                    item("share") {
                        ReplayActionRow(
                            SfIcons.IosShare,
                            stringResource(R.string.share_my_replay),
                            dark,
                            onShare,
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun Heading(state: ReplayState, onPeriodChange: (ReplayPeriod) -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = stringResource(R.string.replay),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = state.summary?.localizedLabel(context) ?: state.period.localizedChip(context),
            style = MaterialTheme.typography.titleMedium,
            color = textColor.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(14.dp))
        PeriodPicker(state.period, onPeriodChange, dark)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PeriodPicker(selected: ReplayPeriod, onSelect: (ReplayPeriod) -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(textColor.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReplayPeriod.entries.forEach { period ->
            val active = period == selected
            val background by animateColorAsState(
                if (active) textColor.copy(alpha = 0.92f) else Color.Transparent,
                tween(160),
                label = "periodChip",
            )
            Text(
                text = period.localizedTab(context),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = if (active) if (dark) Color.Black else Color.White else textColor.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { onSelect(period) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReplayCardRow(
    cards: List<ReplayHeroCard>,
    holder: String,
    memberSince: String?,
    onCardClick: (ReplayStoryPage) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
) {
    val rowState = rememberLazyListState()
    LazyRow(
        modifier = modifier,
        state = rowState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = rowState),
    ) {
        items(cards, key = { it.label }) { card ->
            ReplayCreditCard(
                label = card.label,
                value = card.value,
                detail = card.detail,
                artworkUrl = card.artworkUrl,
                holder = holder,
                memberSince = memberSince,
                onClick = { onCardClick(card.page) },
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

private fun replayChartIndex(summary: ReplaySummary, page: ReplayStoryPage): Int {
    val songs = summary.songRows(CHART_LENGTH)
    val artists = summary.artistRows(CHART_LENGTH)
    fun span(rows: List<ReplayRow>): Int = if (rows.isEmpty()) 0 else rows.size + 2

    return when (page) {
        ReplayStoryPage.SONGS -> 3
        ReplayStoryPage.ARTISTS -> 3 + span(songs)
        ReplayStoryPage.ALBUMS -> 3 + span(songs) + span(artists)
        else -> 0
    }
}

@Composable
private fun ReplayActionRow(icon: ImageVector, label: String, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(textColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = SfIcons.ChevronRight,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun LazyListScope.chart(
    key: String,
    title: String,
    rows: List<ReplayRow>,
    dark: Boolean,
    onClick: (Int) -> Unit,
    circular: Boolean = false,
) {
    if (rows.isEmpty()) return
    item("$key-title") { SectionTitle(title, dark) }
    items(rows, key = { "$key-${it.key}" }) { row ->
        ReplayChartRow(row, circular, dark) { onClick(row.rank - 1) }
    }
    item("$key-gap") { Spacer(Modifier.height(20.dp)) }
}

@Composable
private fun SectionTitle(text: String, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W800,
        color = textColor,
        modifier = Modifier.padding(
            start = PAGE_GUTTER + 4.dp,
            end = PAGE_GUTTER + 4.dp,
            top = 8.dp,
            bottom = 10.dp,
        ),
    )
}

@Composable
private fun ReplayChartRow(row: ReplayRow, circular: Boolean, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    val textVariant = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
    val shape = if (circular) CircleShape else RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(row.rank, AccentRed)
        if (row.artworkUrl != null) {
            AsyncImage(
                model = row.artworkUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(shape),
            )
        } else {
            InitialTile(row.title, 48.dp, shape)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W600,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(row.subtitle, formatListening(LocalContext.current, row.ms))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = textVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.plays > 0) {
            Text(
                text = row.plays.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun Habits(summary: ReplaySummary, dark: Boolean) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        SectionTitleInline(stringResource(R.string.listening_shape), dark)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.songs), summary.distinctSongs.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.artists), summary.distinctArtists.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.albums), summary.distinctAlbums.toString(), dark, Modifier.weight(1f))
        }
        summary.peakHour?.let {
            Spacer(Modifier.height(10.dp))
            Note(stringResource(R.string.listen_most_around, formatHour(context, it)), dark)
        }
        summary.busiestDay?.let {
            Spacer(Modifier.height(6.dp))
            Note(
                stringResource(
                    R.string.biggest_day_note,
                    formatDay(context, it),
                    formatListening(context, summary.busiestDayMs),
                ),
                dark,
            )
        }
    }
}

@Composable
private fun SectionTitleInline(text: String, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W800,
        color = textColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(textColor.copy(alpha = 0.09f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.55f),
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Note(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

@Composable
private fun EmptyReplay(period: ReplayPeriod, dark: Boolean) {
    val emptyColor = if (dark) Color.White else Color.Black
    val emptyVariant = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = SfIcons.Clock,
            contentDescription = null,
            tint = emptyVariant,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.not_enough_listening),
            style = MaterialTheme.typography.titleLarge,
            color = emptyColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (period) {
                ReplayPeriod.THIS_WEEK -> stringResource(R.string.not_enough_this_week)
                ReplayPeriod.THIS_MONTH -> stringResource(R.string.not_enough_this_month)
                else -> stringResource(R.string.replay_empty_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = emptyColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

private const val CHART_LENGTH = 5
