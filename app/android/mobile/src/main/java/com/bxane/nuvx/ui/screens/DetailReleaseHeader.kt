package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.HEADER_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.model.durationMillis
import com.bxane.nuvx.ui.components.CoverPlaceholder
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.components.topBarContentPadding
import com.bxane.nuvx.ui.graphics.FADE_CURVE_STOPS
import com.bxane.nuvx.ui.graphics.smootherStep
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.ui.player.CanvasArtworkPlayer
import com.bxane.nuvx.ui.theme.ArtworkPalette

internal const val SLEEVE_FRACTION = 0.76f
internal const val SLEEVE_MAX_HEIGHT = 0.5f
private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val SLEEVE_SHADOW = 18.dp
internal const val FULL_BLEED_MAX_HEIGHT = 0.62f
private const val FULL_BLEED_FADE_FRACTION = 0.42f
private val FULL_BLEED_TITLE_OVERLAP = 44.dp
private val RELEASE_SIDE_BUTTON = 50.dp
private val RELEASE_ACTION_GAP = 14.dp
private val RELEASE_ACTIONS_MAX_WIDTH = 420.dp

@Composable
internal fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    sleeveSize: Dp,
    fullBleed: Boolean,
    canvas: CanvasArtwork?,
    songs: List<Song>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onArtistClick: ((String, String) -> Unit)?,
    onToggleLibrary: (() -> Unit)?,
    onDownloadAll: (() -> Unit)? = null,
    coverGrid: List<String> = emptyList(),
) {
    val (credit, meta) = page.headerLines(songs.size, songs.playtime())
    val artist = songs.firstOrNull()
    var artFailed by remember(page.thumbnailUrl) { mutableStateOf(false) }
    val bleed = fullBleed && page.thumbnailUrl != null && !artFailed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (bleed) 0.dp else topBarContentPadding(), bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bleed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val height = (placeable.height - FULL_BLEED_TITLE_OVERLAP.roundToPx()).coerceAtLeast(0)
                        layout(placeable.width, height) { placeable.place(0, 0) }
                    }
                    .aspectRatio(1f),
            ) {
                AsyncImage(
                    model = page.thumbnailUrl.artworkAt(HEADER_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { artFailed = it is AsyncImagePainter.State.Error },
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                                        val t = i / FADE_CURVE_STOPS.toFloat()
                                        t to Color.Black.copy(alpha = 1f - smootherStep(t))
                                    },
                                    startY = size.height * (1f - FULL_BLEED_FADE_FRACTION),
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .background(palette.elevated),
                )
                canvas?.let { clip ->
                    CanvasArtworkPlayer(
                        canvas = clip,
                        isPlaying = true,
                        bottomFade = FULL_BLEED_FADE_FRACTION,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        } else Box(
            Modifier
                .size(sleeveSize)
                .shadow(SLEEVE_SHADOW, SLEEVE_SHAPE)
                .background(palette.elevated)
                .thumbnailBorder(SLEEVE_SHAPE),
        ) {
            if (page.thumbnailUrl == null && coverGrid.size == 4) {
                CoverGrid(coverGrid, Modifier.matchParentSize())
            } else if (page.thumbnailUrl == null || artFailed) {
                CoverPlaceholder(
                    icon = page.placeholderIcon(),
                    base = palette.elevated,
                    tint = palette.onBackgroundVariant,
                    modifier = Modifier.matchParentSize(),
                )
            }
            AsyncImage(
                model = page.thumbnailUrl.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { artFailed = it is AsyncImagePainter.State.Error },
                modifier = Modifier.matchParentSize(),
            )
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HEADER_GUTTER),
        )
        if (credit.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = credit,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                ),
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = HEADER_GUTTER)
                    .let { m ->
                        val id = artist?.artistId
                        if (id == null || onArtistClick == null) {
                            m
                        } else {
                            m.clip(RoundedCornerShape(6.dp))
                                .clickable { onArtistClick(id, artist.artist) }
                        }
                    },
            )
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
        }

        if (songs.isNotEmpty()) {
            val library = page.library?.takeIf { onToggleLibrary != null }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .widthIn(max = RELEASE_ACTIONS_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = HEADER_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(RELEASE_ACTION_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page.browseId.startsWith("local:")) {
                    ActionPill(
                        icon = SfIcons.PlayArrow,
                        label = stringResource(R.string.play),
                        onClick = onPlay,
                        filled = true,
                        size = RELEASE_SIDE_BUTTON,
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        icon = SfIcons.Shuffle,
                        label = stringResource(R.string.shuffle),
                        onClick = onShuffle,
                        filled = false,
                        size = RELEASE_SIDE_BUTTON,
                        modifier = Modifier.weight(1f),
                    )
                    onDownloadAll?.let {
                        CircleIconButton(
                            icon = SfIcons.Download,
                            contentDescription = stringResource(R.string.download_all),
                            onClick = it,
                            size = RELEASE_SIDE_BUTTON,
                        )
                    }
                    return@Row
                }
                CircleIconButton(
                    icon = SfIcons.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    onClick = onShuffle,
                    haptic = Haptic.Resume,
                    size = RELEASE_SIDE_BUTTON,
                )
                ActionPill(
                    icon = SfIcons.PlayArrow,
                    label = stringResource(R.string.play),
                    onClick = onPlay,
                    filled = true,
                    size = RELEASE_SIDE_BUTTON,
                    modifier = Modifier.weight(1f),
                )
                if (library != null) {
                    CircleIconButton(
                        icon = if (library.saved) SfIcons.Check else SfIcons.Add,
                        contentDescription = if (library.saved) {
                            stringResource(R.string.remove_from_library)
                        } else {
                            stringResource(R.string.add_to_library)
                        },
                        onClick = { onToggleLibrary?.invoke() },
                        haptic = if (library.saved) Haptic.ToggleOff else Haptic.ToggleOn,
                        size = RELEASE_SIDE_BUTTON,
                    )
                } else {
                    CircleIconButton(
                        icon = SfIcons.Add,
                        contentDescription = stringResource(R.string.add_to_library),
                        onClick = {},
                        size = RELEASE_SIDE_BUTTON,
                        enabled = false,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailPage.headerLines(trackCount: Int, playtime: String? = null): Pair<String, String> {
    val parts = subtitle.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
    val kind = parts.firstOrNull { it.lowercase(Locale.ROOT) in KIND_WORDS }
    val credit = parts.filter { it != year && it != kind }.joinToString(", ")
    val localKind = when {
        LocalGroups.isArtist(browseId) -> stringResource(R.string.artist)
        LocalGroups.isDirectory(browseId) -> stringResource(R.string.folder)
        else -> null
    }
    val meta = listOfNotNull(
        (kind ?: localKind ?: type.localizedLabel())?.capitalizeWords(),
        year,
        trackCount.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.track_count_plural, it, it).capitalizeWords()
        },
        playtime,
    ).joinToString(" · ")
    return credit to meta
}

private fun DetailPage.placeholderIcon(): ImageVector = deviceFolderIcon(browseId) ?: when {
    LocalGroups.isArtist(browseId) -> SfIcons.Person
    OfflinePlaylists.isLikedPage(browseId) -> SfIcons.HeartFill
    LocalGroups.isDirectory(browseId) -> SfIcons.Folder
    type == BrowseType.ALBUM -> SfIcons.Album
    type == BrowseType.PLAYLIST -> SfIcons.QueueMusic
    else -> SfIcons.MusicNote
}

private fun String.capitalizeWords(): String =
    split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

@Composable
private fun BrowseType.localizedLabel(): String? = when (this) {
        BrowseType.ALBUM -> stringResource(R.string.album)
        BrowseType.PLAYLIST -> stringResource(R.string.playlist)
        BrowseType.ARTIST -> stringResource(R.string.artist)
        BrowseType.OTHER -> null
    }

@Composable
private fun List<Song>.playtime(): String? {
    val minutes = sumOf { it.durationMillis() } / 60_000
    return when {
        minutes <= 0 -> null
        minutes < 60 -> stringResource(R.string.minutes_short, minutes.toInt())
        else -> stringResource(R.string.hours_minutes_short, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}
