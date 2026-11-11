package com.bxane.nuvx.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.EntityType
import com.bxane.nuvx.data.model.SearchHistoryEntity
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.toSong
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.SearchPlayingBars
import com.bxane.nuvx.ui.components.SwipeToQueueBox
import com.bxane.nuvx.ui.components.contextClick
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.icons.SfIcons
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

internal fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        val song = entity.toSong()
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            onClick = { onClick(entity) },
            onLongPress = song?.let { { onSongLongPress(it) } },
            onRemove = { onRemove(entity.id) },
            onSwipeToQueue = song?.let { { onSongSwipe(it) } },
        )
    }
}

internal fun LazyListScope.recentAddonSearches(
    history: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "addon-recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "addon-recent:$it" }) { term ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(term) }
                .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                SfIcons.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = term,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onRemove(term) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Close,
                    contentDescription = stringResource(Res.string.recent_search_remove, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistoryEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onRemove: () -> Unit,
    onSwipeToQueue: (() -> Unit)? = null,
) {
    SwipeToQueueBox(
        onSwipeToQueue = onSwipeToQueue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .longPressMenuClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                    liftPadding = PaddingValues(start = PAGE_GUTTER, end = 8.dp),
                )
                .contextClick(onLongPress)
                .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp)) {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .thumbnailBorder(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (isCurrent && isPlaying) {
                    SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entity.subtitle.ifBlank { entity.entityType.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Close,
                    contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
