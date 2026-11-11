package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.components.trackArtSize
import com.bxane.nuvx.ui.components.contextClick
import com.bxane.nuvx.sharedui.resources.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.bxane.nuvx.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.bxane.nuvx.ui.components.ShelfRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.RowMoreButton
import com.bxane.nuvx.ui.components.SearchPlayingBars
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.components.trackColumnWidth

internal const val RECENTS_TITLE = "Recents"
internal const val RECENT_TRACKS_PER_COLUMN = 4

internal fun List<ShelfItem>.playingItemMatcher(song: Song?): (ShelfItem) -> Boolean {
    song ?: return { false }
    if (any { it.videoId == song.videoId }) return { it.videoId == song.videoId }
    val byName = firstOrNull { it.videoId != null && it.title == song.title && it.subtitle == song.artist }
        ?: return { false }
    return { it == byName }
}

@Composable
internal fun RecentShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        RecentSectionHeader(title = localizeShelfTitle(shelf.title), subtitle = localizeShelfSubtitle(shelf.subtitle))
        val isCurrentItem = shelf.items.playingItemMatcher(currentSong)
        BoxWithConstraints {
            val columnWidth = trackColumnWidth(maxWidth)
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.items.chunked(RECENT_TRACKS_PER_COLUMN)) { column ->
                    Column(Modifier.width(columnWidth)) {
                        column.forEach { item ->
                            RecentTrackRow(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongPress = onItemLongPress?.let { { it(item) } },
                                isCurrent = isCurrentItem(item),
                                isPlaying = isCurrentItem(item) && isPlaying,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentTrackRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    CompactTrackRow(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.thumbnailUrl,
        onClick = onClick,
        onLongPress = onLongPress,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        isVideo = item.isVideo,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTrackRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isVideo: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.trackArtSize(48.dp, isVideo)) {
            AsyncImage(
                model = thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(7.dp))
                    .thumbnailBorder(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLongPress != null) {
            RowMoreButton(onClick = onLongPress)
        }
    }
}
