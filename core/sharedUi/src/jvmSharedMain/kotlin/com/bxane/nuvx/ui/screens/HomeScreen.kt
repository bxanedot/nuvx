package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import com.bxane.nuvx.ui.festive.GoldenLogo
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.ShelfLayout
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.TopPick
import com.bxane.nuvx.data.model.TopPickMood
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.bxane.nuvx.ui.components.PullToRefresh
import com.bxane.nuvx.ui.components.SignInBanner
import com.bxane.nuvx.ui.components.feedMoreSkeleton
import com.bxane.nuvx.ui.components.feedSkeleton
import com.bxane.nuvx.ui.components.recentlyPlayedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String?,
    festiveLogo: Boolean = false,
    showLogo: Boolean = true,
    onFestiveLogoClick: (() -> Unit)? = null,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    topPicks: List<TopPick>? = null,
    topPicksLoading: Boolean = false,
    listenerName: String? = null,
    onTopPickClick: ((TopPick, String) -> Unit)? = null,
    onTopPickLongPress: ((TopPick) -> Unit)? = null,
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    onMoodsShowAll: () -> Unit = {},
    artworkMotion: Boolean = true,
) {
    val feedReady = state !is UiState.Loading
    CompositionLocalProvider(
        LocalArtworkMotion provides (artworkMotion && feedReady),
        LocalArtworkImages provides feedReady,
    ) {
    PausedWhileScrolling(listState) {

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (title != null) {
                PaddingValues(
                    top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                    bottom = contentPadding.calculateBottomPadding(),
                )
            } else {
                contentPadding
            },
        ) {
            if (title != null) {
                item { HomeTitle(title, festiveLogo = festiveLogo, showLogo = showLogo, onFestiveLogoClick = onFestiveLogoClick) }
            }
            if (!signedIn && onSignIn != null) {
                item {
                    SignInBanner(
                        onSignIn = onSignIn,
                        modifier = Modifier.padding(bottom = 8.dp),
                        motion = LocalArtworkMotion.current && !listState.isScrollInProgress,
                    )
                }
            }
            if (onTopPickClick != null) {
                when {
                    !topPicks.isNullOrEmpty() -> item(key = "top-picks") {
                        TopPicksShelf(
                            picks = topPicks,
                            listenerName = listenerName,
                            onClick = onTopPickClick,
                            onLongPress = onTopPickLongPress,
                        )
                    }
                    topPicks == null && topPicksLoading -> topPicksSkeleton()
                }
            }
            when (state) {
                is UiState.Loading -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton()
                    }
                    feedSkeleton()
                }
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton()
                    }
                    itemsIndexedShelves(
                        shelves = state.data,
                        onMoodClick = onMoodClick,
                        onMoodsShowAll = onMoodsShowAll,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                    )
                    if (loadingMore) feedMoreSkeleton()
                }
            }
        }
    }

    }
    }

    if (onLoadMore != null && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    onMoodClick: ((TopPickMood, String) -> Unit)? = null,
    onMoodsShowAll: () -> Unit = {},
) {
    shelves.forEachIndexed { index, shelf ->
        if (index == 1 && onMoodClick != null) {
            item(key = "find-your-mood") { FindYourMoodShelf(onMoodClick, onMoodsShowAll) }
        }
        item(key = shelf.title + index) {
            val openItem: (ShelfItem) -> Unit = { item -> onItemClick(item, shelf.title) }
            if (shelf.layout == ShelfLayout.LIST ||
                (index == 0 && shelf.title.equals(RECENTS_TITLE, ignoreCase = true))
            ) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}
