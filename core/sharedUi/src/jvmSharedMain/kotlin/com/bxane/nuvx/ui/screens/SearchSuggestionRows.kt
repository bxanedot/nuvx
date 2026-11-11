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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.data.model.BrowseItem
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.model.playingRowMatcher
import com.bxane.nuvx.sharedui.resources.*
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.SearchPlayingBars
import com.bxane.nuvx.ui.components.contextClick
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.components.trackArtSize
import com.bxane.nuvx.ui.icons.SfIcons
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

internal fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    itemsIndexed(suggestions.drop(1).take(3), key = { _, term -> "suggest:$term" }) { _, term ->
        SuggestionRow(
            term = term,
            isQueryAction = false,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

@Composable
private fun SuggestionRow(
    term: String,
    isQueryAction: Boolean,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = PAGE_GUTTER,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SfIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = if (isQueryAction) """${stringResource(Res.string.search)} "$term"""" else term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.NorthWest,
                    contentDescription = stringResource(Res.string.recent_search_edit, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.size(40.dp))
        }
    }
}

internal fun LazyListScope.searchTypeaheadDropdown(
    typeaheadResults: List<SearchResult>,
    onSongClick: (Song) -> Unit,
    onSongLongPress: ((Song) -> Unit)?,
    onBrowseClick: (BrowseItem) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    val isCurrentRow = playingRowMatcher(
        typeaheadResults.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song },
        currentSong,
    )
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = isCurrentRow(result.song),
                isPlaying = isPlaying && isCurrentRow(result.song),
            )
            is SearchResult.Browse -> BrowseRow(
                item = result.item,
                onClick = { onBrowseClick(result.item) },
                onLongPress = onSongLongPress?.let { { } },
            )
            is SearchResult.TopTrack -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = isCurrentRow(result.song),
                isPlaying = isPlaying && isCurrentRow(result.song),
            )
        }
    }
}

@Composable
private fun TypeaheadSongRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                liftPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.trackArtSize(52.dp, song.isVideo)) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(song.artist).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                liftPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(8.dp),
                )
                .thumbnailBorder(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(8.dp),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle.ifBlank { item.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal const val BLOCKED_ROW_ALPHA = 0.38f
