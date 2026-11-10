package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.components.bounceOnReveal
import com.bxane.nuvx.ui.components.RevealIcon
import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.bxane.nuvx.ui.components.ShelfRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.settings.LibrarySort
import com.bxane.nuvx.ui.AppUi
import com.bxane.nuvx.ui.components.LIBRARY_GRID_SPACING
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.PullToRefresh
import com.bxane.nuvx.ui.components.SHELF_CARD_WIDTH
import com.bxane.nuvx.ui.components.libraryGrid
import com.bxane.nuvx.ui.components.librarySkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    onShowAll: (HomeShelf) -> Unit,
    replay: @Composable () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    links: List<LibraryLink>,
    deviceItems: List<ShelfItem>,
    showTitle: Boolean = true,
) {
    val pinnedPlaylists by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    val onDevice = stringResource(Res.string.on_device)
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            if (showTitle) {
                item {
                    Text(
                        text = stringResource(Res.string.library),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    )
                }
            }
            item(key = "replay") { replay() }
            if (links.isNotEmpty()) {
                item(key = "links") { LibraryLinkList(links = links, onClick = onShelfItemClick) }
            }
            if (deviceItems.isNotEmpty()) {
                item(key = "shelf:$onDevice") {
                    val onDeviceShelf = HomeShelf(title = onDevice, items = deviceItems)
                    LibraryGridShelf(
                        shelf = onDeviceShelf,
                        onItemClick = onShelfItemClick,
                        onItemLongPress = onShelfItemLongPress,
                        onShowAll = { onShowAll(onDeviceShelf) },
                    )
                }
            }
            if (!signedIn) {
                item(key = "shelf:$PLAYLISTS") {
                    val emptyPlaylists = HomeShelf(PLAYLISTS, emptyList())
                    PlaylistShelf(
                        shelf = emptyPlaylists,
                        onItemClick = onShelfItemClick,
                        onItemLongPress = onShelfItemLongPress,
                        onNewPlaylist = onNewPlaylist,
                        onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                        onShowAll = { onShowAll(emptyPlaylists) },
                    )
                }
                item {
                    MessageState(
                        message = stringResource(Res.string.library_sign_in_description),
                        actionLabel = stringResource(Res.string.sign_in),
                        onAction = onSignIn,
                    )
                }
                return@LazyColumn
            }
            when (state) {
                is UiState.Loading -> librarySkeleton()
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    val shelves = state.data.shelves
                    if (shelves.none { it.title == PLAYLISTS }) {
                        item(key = "shelf:$PLAYLISTS") {
                            val emptyPlaylists = HomeShelf(PLAYLISTS, emptyList())
                            PlaylistShelf(
                                shelf = emptyPlaylists,
                                onItemClick = onShelfItemClick,
                                onItemLongPress = onShelfItemLongPress,
                                onNewPlaylist = onNewPlaylist,
                                onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                                onShowAll = { onShowAll(emptyPlaylists) },
                            )
                        }
                    }
                    shelves.forEach { shelf ->
                        item(key = "shelf:${shelf.title}") {
                            if (shelf.title == PLAYLISTS) {
                                val pinnedFirst = shelf.pinnedFirst(pinnedPlaylists)
                                PlaylistShelf(
                                    shelf = pinnedFirst,
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onNewPlaylist = onNewPlaylist,
                                    onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                                    onShowAll = { onShowAll(pinnedFirst) },
                                    pinnedPlaylists = pinnedPlaylists,
                                )
                            } else {
                                LibraryGridShelf(
                                    shelf = shelf,
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onShowAll = { onShowAll(shelf) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class LibraryLink(val item: ShelfItem, val icon: ImageVector, val logo: DrawableResource? = null)

@Composable
private fun LibraryLinkList(links: List<LibraryLink>, onClick: (ShelfItem) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 22.dp)) {
        links.forEachIndexed { index, link ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clickable { onClick(link.item) }
                    .padding(horizontal = PAGE_GUTTER),
            ) {
                if (link.logo != null) {
                    Icon(
                        painter = painterResource(link.logo),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(LINK_ICON_SIZE).bounceOnReveal(),
                    )
                } else {
                    RevealIcon(link.icon, MaterialTheme.colorScheme.primary, LINK_ICON_SIZE)
                }
                Spacer(Modifier.width(LINK_ICON_GAP))
                Text(
                    text = link.item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = SfIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (index < links.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = PAGE_GUTTER + LINK_ICON_SIZE + LINK_ICON_GAP),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private val LINK_ICON_SIZE = 24.dp
private val LINK_ICON_GAP = 16.dp

@Composable
private fun PlaylistShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    onShowAll: () -> Unit,
    pinnedPlaylists: List<String> = emptyList(),
) {
    LibraryGridShelf(
        shelf = shelf,
        onItemClick = onItemClick,
        onItemLongPress = onItemLongPress,
        onShowAll = onShowAll,
        pinnedPlaylists = pinnedPlaylists,
        leadingCard = {
            Row(horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING)) {
                NewShelfCard(
                    icon = SfIcons.Add,
                    label = stringResource(Res.string.new_playlist),
                    subtitle = stringResource(Res.string.saved_to_youtube_music),
                    onClick = onNewPlaylist,
                )
                if (onImportSpotifyPlaylist != null) {
                    NewShelfCard(
                        icon = SfIcons.Download,
                        label = stringResource(Res.string.import_spotify),
                        subtitle = stringResource(Res.string.import_spotify_subtitle),
                        onClick = onImportSpotifyPlaylist,
                        logo = Res.drawable.spotify_logo,
                    )
                }
            }
        },
    )
}

private const val LIBRARY_ROW_MAX_ITEMS = 5

@Composable
internal fun LibraryGridShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onShowAll: () -> Unit,
    leadingCard: (@Composable () -> Unit)? = null,
    pinnedPlaylists: List<String> = emptyList(),
) {
    val leadingCount = if (leadingCard != null) 1 else 0
    val visibleItems = shelf.items.take((LIBRARY_ROW_MAX_ITEMS - leadingCount).coerceAtLeast(0))
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(
            title = shelf.title,
            subtitle = shelf.subtitle,
            onShowAll = onShowAll.takeIf { shelf.items.size + leadingCount > LIBRARY_ROW_MAX_ITEMS },
        )
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(visibleItems) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) },
                    isPinned = item.browseId != null && item.browseId in pinnedPlaylists,
                )
            }
        }
    }
}

@Composable
fun LibraryGridPage(
    shelf: HomeShelf,
    gridState: LazyGridState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onNewPlaylist: (() -> Unit)? = null,
) {
    val pinnedPlaylists by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    val librarySort by AppUi.host.librarySort.collectAsStateWithLifecycle()
    val sortedShelf = shelf.pinnedFirst(pinnedPlaylists).sortedForLibrary(librarySort)
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
            if (onNewPlaylist != null) {
                item(key = "leading") {
                    NewShelfCard(
                        icon = SfIcons.Add,
                        label = stringResource(Res.string.new_playlist),
                        subtitle = stringResource(Res.string.saved_to_youtube_music),
                        onClick = onNewPlaylist,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(sortedShelf.items, key = { it.browseId ?: it.title }) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) },
                    modifier = Modifier.fillMaxWidth(),
                    isPinned = item.browseId != null && item.browseId in pinnedPlaylists,
                )
            }
        }
    }
}

private fun HomeShelf.pinnedFirst(pinned: List<String>): HomeShelf {
    if (pinned.isEmpty()) return this
    val byId = items.filter { it.browseId != null }.associateBy { it.browseId }
    val pinnedItems = pinned.mapNotNull { byId[it] }
    if (pinnedItems.isEmpty()) return this
    val pinnedSet = pinnedItems.toSet()
    return copy(items = pinnedItems + items.filter { it !in pinnedSet })
}

private fun HomeShelf.sortedForLibrary(sort: LibrarySort): HomeShelf = when (sort) {
    LibrarySort.DEFAULT -> this
    LibrarySort.TITLE_ASC -> copy(items = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }))
    LibrarySort.TITLE_DESC -> copy(
        items = items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }),
    )
}

private const val PLAYLISTS = YtMusicRepository.PLAYLISTS_SHELF
