package com.bxane.nuvx.ui.screens

import java.util.Locale
import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalGroup
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.CARD_ART_PX
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.model.playingRowMatcher
import com.bxane.nuvx.data.settings.LibraryViewType
import com.bxane.nuvx.data.settings.LocalMusicSort
import com.bxane.nuvx.download.DownloadState
import com.bxane.nuvx.download.DownloadedCollection
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.ui.components.CoverPlaceholder
import com.bxane.nuvx.ui.components.ExplicitSongTitle
import com.bxane.nuvx.ui.components.LIBRARY_GRID_SPACING
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.ROW_DIVIDER_INSET
import com.bxane.nuvx.ui.components.SearchPlayingBars
import com.bxane.nuvx.ui.components.SongRow
import com.bxane.nuvx.ui.components.longPressMenuClickable
import com.bxane.nuvx.ui.components.rememberRemoteArtworkUrl
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.theme.ArtworkPalette

class LocalFolder(
    val state: LocalFolderState,
    val sort: LocalMusicSort,
    val viewType: LibraryViewType,
    val collections: List<DownloadedCollection> = emptyList(),
    val selectable: Boolean = false,
    val onOpenGroup: (LocalGroup) -> Unit,
    val onGroupLongPress: (LocalGroup) -> Unit,
    val onDelete: ((List<Song>) -> Unit)? = null,
    val onUpload: ((List<Song>) -> Unit)? = null,
    val onDownload: ((List<Song>) -> Unit)? = null,
    val onRemoveFromCache: ((List<Song>) -> Unit)? = null,
    val tabs: List<LocalFolderTab> = LocalFolderTab.BASIC,
    val playlists: List<LocalGroup> = emptyList(),
    val onCreatePlaylist: (() -> Unit)? = null,
)

enum class LocalFolderTab {
    SONGS,
    ARTISTS,
    ALBUMS,

    PLAYLISTS,

    FOLDERS,
    ;

    companion object {
        val BASIC = listOf(SONGS, ARTISTS, ALBUMS)

        val DEVICE = entries.toList()
    }
}

@Stable
class LocalFolderState {
    var tab by mutableStateOf(LocalFolderTab.SONGS)

    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
        private set
    val selecting: Boolean get() = selectedIds.isNotEmpty()

    fun select(song: Song) {
        selectedIds = selectedIds + song.videoId
    }

    fun toggle(song: Song) {
        selectedIds = if (song.videoId in selectedIds) selectedIds - song.videoId else selectedIds + song.videoId
    }

    fun select(group: LocalGroup) {
        selectedGroups = selectedGroups + group.browseId
        selectedIds = selectedIds + group.songs.map { it.videoId }
    }

    fun toggle(group: LocalGroup) {
        if (group.browseId in selectedGroups) {
            selectedGroups = selectedGroups - group.browseId
            selectedIds = selectedIds - group.songs.mapTo(HashSet()) { it.videoId }
        } else {
            select(group)
        }
    }

    fun selectAll(songs: List<Song>) {
        selectedIds = songs.mapTo(linkedSetOf()) { it.videoId }
    }

    fun clear() {
        selectedIds = emptySet()
        selectedGroups = emptySet()
    }
}

@Composable
fun rememberLocalFolderGroups(
    browseId: String,
    folder: LocalFolder,
    tab: LocalFolderTab,
    songs: List<Song>,
    query: String,
): List<LocalGroup> = remember(browseId, tab, songs, folder.collections, folder.playlists, query) {
    fun LocalGroup.matches() = query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        subtitle.contains(query, ignoreCase = true)
    when (tab) {
        LocalFolderTab.SONGS -> emptyList()
        LocalFolderTab.ARTISTS -> LocalGroups.artistsOf(
            browseId,
            songs,
            folder.collections.filter { it.artist }
                .mapNotNull { c -> c.thumbnailUrl?.let { c.title.lowercase(Locale.ROOT) to it } }.toMap(),
        ).filter { it.matches() }
        LocalFolderTab.ALBUMS -> LocalGroups.albumsOf(browseId, songs, folder.collections).filter { it.matches() }
        LocalFolderTab.PLAYLISTS -> folder.playlists.filter { it.matches() }
        LocalFolderTab.FOLDERS -> LocalGroups.foldersOf(browseId, songs).filter { it.matches() }
    }
}

internal fun LazyListScope.localFolderItems(
    folder: LocalFolder,
    state: LocalFolderState,
    songs: List<Song>,
    groups: List<LocalGroup>,
    query: String,
    columns: Int,
    palette: ArtworkPalette,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    val tabs = folder.tabs
    item(key = "folder-tabs") {
        SegmentedControl(
            options = tabs.map { it.label() },
            selectedIndex = tabs.indexOf(state.tab).coerceAtLeast(0),
            onSelect = { state.tab = tabs[it] },
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 14.dp),
        )
    }

    if (state.tab == LocalFolderTab.PLAYLISTS && query.isBlank()) {
        folder.onCreatePlaylist?.let { create ->
            item(key = "folder-new-playlist") {
                NewPlaylistRow(palette, onClick = create)
                FolderDivider(palette)
            }
        }
    }

    val grid = folder.viewType == LibraryViewType.GRID
    val empty = when (state.tab) {
        LocalFolderTab.SONGS -> songs.isEmpty()
        else -> groups.isEmpty()
    }
    if (empty) {
        item(key = "folder-empty") {
            MessageState(
                if (query.isNotBlank()) {
                    stringResource(R.string.nothing_matches, query)
                } else if (state.tab == LocalFolderTab.ALBUMS) {
                    stringResource(R.string.no_local_albums)
                } else if (state.tab == LocalFolderTab.FOLDERS) {
                    stringResource(R.string.no_local_folders)
                } else {
                    stringResource(R.string.no_tracks_here)
                },
            )
        }
        return
    }

    val songClick: (Int) -> Unit = { index ->
        if (state.selecting) state.toggle(songs[index]) else onSongClick(songs, index)
    }
    val songHold: (Song) -> Unit = { song ->
        if (folder.selectable) state.select(song) else onSongLongPress(song)
    }
    val groupClick: (LocalGroup) -> Unit = { group ->
        if (state.selecting && group.type != BrowseType.OTHER) state.toggle(group) else folder.onOpenGroup(group)
    }
    val groupHold: (LocalGroup) -> Unit = { group ->
        if (folder.selectable && group.type != BrowseType.OTHER) state.select(group) else folder.onGroupLongPress(group)
    }

    val isCurrentRow = playingRowMatcher(songs, currentSong)
    when (state.tab) {
        LocalFolderTab.SONGS -> if (grid) {
            gridRows(songs.indices.toList(), columns, key = { "song-${songs[it].videoId}-$it" }) { index ->
                val song = songs[index]
                SongGridCard(
                    song = song,
                    selected = song.videoId in state.selectedIds,
                    isCurrent = isCurrentRow(song),
                    isPlaying = isCurrentRow(song) && isPlaying,
                    palette = palette,
                    onClick = { songClick(index) },
                    onLongPress = { songHold(song) },
                )
            }
        } else {
            itemsIndexed(songs, key = { index, song -> "song-${song.videoId}-$index" }) { index, song ->
                SongRow(
                    song = song,
                    selected = song.videoId in state.selectedIds,
                    isCurrent = isCurrentRow(song),
                    isPlaying = isCurrentRow(song) && isPlaying,
                    searchPlayingStyle = true,
                    activeTint = palette.accent,
                    subtitleColor = palette.onBackgroundVariant,
                    rowBackground = Color.Transparent,
                    downloadedTint = null,
                    onClick = { songClick(index) },
                    onLongPress = { songHold(song) },
                    onMore = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    trailing = folder.onDownload?.let { download ->
                        { RowDownloadButton(song, palette) { download(listOf(song)) } }
                    },
                )
                if (index < songs.lastIndex) FolderDivider(palette)
            }
        }

        LocalFolderTab.ARTISTS -> if (grid) {
            gridRows(groups, columns, key = { it.browseId }) { group ->
                ArtistGridCard(group, palette, onClick = { groupClick(group) }, onLongPress = { groupHold(group) })
            }
        } else {
            items(groups, key = { it.browseId }) { group ->
                ArtistRow(group, palette, onClick = { groupClick(group) }, onLongPress = { groupHold(group) })
                FolderDivider(palette)
            }
        }

        LocalFolderTab.ALBUMS, LocalFolderTab.PLAYLISTS, LocalFolderTab.FOLDERS -> if (grid) {
            gridRows(groups, columns, key = { it.browseId }) { group ->
                AlbumGridCard(
                    group = group,
                    palette = palette,
                    selected = group.browseId in state.selectedGroups,
                    onClick = groupClick,
                    onLongPress = { groupHold(group) },
                )
            }
        } else {
            items(groups, key = { it.browseId }) { group ->
                AlbumRow(
                    group = group,
                    palette = palette,
                    selected = group.browseId in state.selectedGroups,
                    onClick = groupClick,
                    onLongPress = { groupHold(group) },
                )
                FolderDivider(palette)
            }
        }
    }
}

@Composable
private fun LocalFolderTab.label(): String = stringResource(
    when (this) {
        LocalFolderTab.SONGS -> R.string.songs
        LocalFolderTab.ARTISTS -> R.string.artists
        LocalFolderTab.ALBUMS -> R.string.albums
        LocalFolderTab.PLAYLISTS -> R.string.playlists
        LocalFolderTab.FOLDERS -> R.string.folders
    },
)

@Composable
private fun NewPlaylistRow(palette: ArtworkPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .background(palette.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfIcons.Add,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.new_playlist),
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun <T> LazyListScope.gridRows(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    val rows = items.chunked(columns.coerceAtLeast(1))
    items(rows.size, key = { "grid-$it-${key(rows[it].first())}" }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
        ) {
            rows[row].forEach { entry -> Box(Modifier.weight(1f)) { card(entry) } }
            repeat(columns - rows[row].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun FolderDivider(palette: ArtworkPalette) {
    HorizontalDivider(
        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
        thickness = 0.5.dp,
        color = palette.divider,
    )
}

@Composable
private fun selectionFill(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent

private val CARD_SHAPE = RoundedCornerShape(12.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongGridCard(
    song: Song,
    selected: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(CARD_SHAPE)
            .background(selectionFill(selected))
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CARD_SHAPE)
                .thumbnailBorder(CARD_SHAPE),
            contentAlignment = Alignment.Center,
        ) {
            CoverPlaceholder(SfIcons.MusicNote, Modifier.fillMaxSize())
            if (song.thumbnailUrl != null) {
                AsyncImage(
                    model = song.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))
            } else if (isCurrent) {
                Icon(
                    SfIcons.GraphicEq,
                    contentDescription = stringResource(R.string.now_playing),
                    tint = palette.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ExplicitSongTitle(
            song = song,
            style = MaterialTheme.typography.titleMedium,
            color = if (isCurrent) palette.accent else palette.onBackground,
        )
        Text(
            text = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
            style = MaterialTheme.typography.bodySmall,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistAvatar(group: LocalGroup, modifier: Modifier) {
    val art = remember(group) { group.thumbnailUrl ?: group.songs.firstNotNullOfOrNull { it.thumbnailUrl } }
    Box(modifier.clip(CircleShape).thumbnailBorder(CircleShape)) {
        CoverPlaceholder(SfIcons.Person, Modifier.fillMaxSize(), iconFraction = 0.5f)
        if (art != null) {
            AsyncImage(
                model = art.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistRow(
    group: LocalGroup,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                liftPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistAvatar(group, Modifier.size(52.dp))
        Spacer(Modifier.width(16.dp))
        GroupLabels(
            title = group.title,
            subtitle = pluralStringResource(R.plurals.song_count_plural, group.songs.size, group.songs.size),
            palette = palette,
            modifier = Modifier.weight(1f),
        )
        TrailingGlyph(selected = false, palette = palette)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistGridCard(
    group: LocalGroup,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(CARD_SHAPE)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(group, Modifier.fillMaxWidth(0.88f).aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = pluralStringResource(R.plurals.song_count_plural, group.songs.size, group.songs.size),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocalGroup.albumSubtitle(): String {
    val count = pluralStringResource(R.plurals.song_count_plural, songs.size, songs.size)
    return when {
        LocalGroups.isDirectory(browseId) -> if (subtitle.isNotBlank()) "$subtitle · $count" else count
        type == BrowseType.PLAYLIST -> "${stringResource(R.string.playlist)} · $count"
        subtitle.isNotBlank() && subtitle != title -> "$subtitle · $count"
        else -> count
    }
}

@Composable
private fun LocalGroup.coverArt(): String? {
    val fetched = rememberRemoteArtworkUrl(songs.firstOrNull())
    return thumbnailUrl ?: fetched
}

private fun LocalGroup.placeholderIcon(): ImageVector = when {
    OfflinePlaylists.isLikedPage(browseId) -> SfIcons.HeartFill
    LocalGroups.isDirectory(browseId) -> SfIcons.Folder
    type == BrowseType.PLAYLIST -> SfIcons.QueueMusic
    else -> SfIcons.Album
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridCard(
    group: LocalGroup,
    palette: ArtworkPalette,
    selected: Boolean,
    onClick: (LocalGroup) -> Unit,
    onLongPress: () -> Unit,
) {
    val art = group.coverArt()
    Column(
        modifier = Modifier
            .clip(CARD_SHAPE)
            .background(selectionFill(selected))
            .longPressMenuClickable(onClick = { onClick(group.copy(thumbnailUrl = art)) }, onLongClick = onLongPress)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CARD_SHAPE)
                .thumbnailBorder(CARD_SHAPE),
        ) {
            CoverPlaceholder(group.placeholderIcon(), Modifier.fillMaxSize())
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = group.albumSubtitle(),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRow(
    group: LocalGroup,
    palette: ArtworkPalette,
    selected: Boolean,
    onClick: (LocalGroup) -> Unit,
    onLongPress: () -> Unit,
) {
    val art = group.coverArt()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(selectionFill(selected))
            .longPressMenuClickable(
                onClick = { onClick(group.copy(thumbnailUrl = art)) },
                onLongClick = onLongPress,
                liftPadding = PaddingValues(horizontal = PAGE_GUTTER),
            )
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(shape).thumbnailBorder(shape)) {
            CoverPlaceholder(group.placeholderIcon(), Modifier.fillMaxSize(), iconFraction = 0.46f)
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        GroupLabels(
            title = group.title,
            subtitle = group.albumSubtitle(),
            palette = palette,
            modifier = Modifier.weight(1f),
        )
        TrailingGlyph(selected = selected, palette = palette)
    }
}

@Composable
private fun GroupLabels(title: String, subtitle: String, palette: ArtworkPalette, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrailingGlyph(selected: Boolean, palette: ArtworkPalette) {
    Icon(
        imageVector = if (selected) SfIcons.CheckCircle else SfIcons.ChevronRight,
        contentDescription = if (selected) stringResource(R.string.selected) else null,
        tint = if (selected) MaterialTheme.colorScheme.primary else palette.onBackgroundVariant,
        modifier = Modifier.size(if (selected) 22.dp else 16.dp),
    )
}

@Composable
internal fun LocalFolderSelectionBar(
    folder: LocalFolder,
    state: LocalFolderState,
    songs: List<Song>,
    modifier: Modifier = Modifier,
) {
    val count = state.selectedIds.size
    val allSelected = songs.isNotEmpty() && songs.all { it.videoId in state.selectedIds }
    fun chosen() = songs.filter { it.videoId in state.selectedIds }.also { state.clear() }
    val shape = RoundedCornerShape(14.dp)
    val dark = isSystemInDarkTheme()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(10.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.selectAll(songs) }, enabled = !allSelected) {
            Icon(
                SfIcons.Check,
                contentDescription = stringResource(R.string.select_all),
                tint = if (dark) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "$count ${stringResource(R.string.selected)}",
            style = MaterialTheme.typography.titleSmall,
            color = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        folder.onUpload?.let { upload ->
            SelectionAction(
                icon = SfIcons.FileUpload,
                label = stringResource(R.string.upload_to_webdav),
                color = MaterialTheme.colorScheme.primary,
                enabled = count > 0,
                onClick = { upload(chosen()) },
            )
        }
        folder.onDownload?.let { download ->
            SelectionAction(
                icon = SfIcons.Download,
                label = stringResource(R.string.download),
                color = MaterialTheme.colorScheme.primary,
                enabled = count > 0,
                onClick = { download(chosen()) },
            )
        }
        folder.onRemoveFromCache?.let { remove ->
            SelectionAction(
                icon = SfIcons.Delete,
                label = stringResource(R.string.cached_song_remove),
                color = DESTRUCTIVE,
                enabled = count > 0,
                onClick = { remove(chosen()) },
            )
        }
        folder.onDelete?.let { delete ->
            SelectionAction(
                icon = SfIcons.Delete,
                label = stringResource(R.string.delete),
                color = DESTRUCTIVE,
                enabled = count > 0,
                onClick = { delete(chosen()) },
            )
        }
        TextButton(onClick = state::clear) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun RowDownloadButton(song: Song, palette: ArtworkPalette, onDownload: () -> Unit) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val state = active[song.videoId]
    val done = state == null && song.videoId in saved
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = !done) {
                when (state) {
                    is DownloadState.Queued, is DownloadState.Running -> Downloads.cancel(song.videoId)
                    else -> onDownload()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DownloadState.Queued -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = palette.accent,
            )
            is DownloadState.Running -> if (state.fraction > 0f) {
                CircularProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
            }
            is DownloadState.Failed -> Icon(
                SfIcons.ErrorOutline,
                contentDescription = stringResource(R.string.retry),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            null -> Icon(
                if (done) SfIcons.DownloadDone else SfIcons.Download,
                contentDescription = stringResource(if (done) R.string.downloaded else R.string.download),
                tint = if (done) palette.accent else palette.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color)
    }
}

private val DESTRUCTIVE = Color(0xFFFF453A)

@Composable
internal fun LocalMusicSort.localizedLabel(): String = when (this) {
    LocalMusicSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LocalMusicSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    LocalMusicSort.DATE_ADDED -> stringResource(R.string.sort_date_added)
    LocalMusicSort.DATE_MODIFIED -> stringResource(R.string.sort_date_modified)
}
