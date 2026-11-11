package com.bxane.nuvx.ui

import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MainFeedRefreshController(
    private val scope: CoroutineScope,
    private val signedIn: () -> Boolean,
    private val home: StateFlow<UiState<List<HomeShelf>>>,
    private val library: StateFlow<UiState<LibraryPage>>,
    private val listenerKey: () -> String?,
    private val refreshHome: suspend (String?) -> Unit,
    private val refreshExplore: suspend () -> Unit,
    private val refreshLibrary: suspend (String?) -> Unit,
    private val refreshHomeRecents: () -> Unit,
    private val loadPlaylists: () -> Unit,
) {
    private var libraryStale = false
    private var homeStale = false

    private val _refreshing = MutableStateFlow<Set<MainViewModel.Feed>>(emptySet())
    val refreshing: StateFlow<Set<MainViewModel.Feed>> = _refreshing.asStateFlow()

    fun markLibraryStale() {
        libraryStale = true
    }

    fun markHomeStale() {
        homeStale = true
    }

    fun onLibraryShown() {
        loadPlaylists()
        if (!libraryStale) return
        libraryStale = false
        if (library.value is UiState.Success) refresh(MainViewModel.Feed.LIBRARY)
    }

    fun onHomeShown() {
        if (!homeStale) return
        homeStale = false
        if (home.value is UiState.Success) refreshHomeRecents()
    }

    fun refresh(feed: MainViewModel.Feed) {
        if (feed in _refreshing.value) return
        if (feed == MainViewModel.Feed.LIBRARY && !signedIn()) return
        val identity = listenerKey()
        _refreshing.value = _refreshing.value + feed
        scope.launch {
            when (feed) {
                MainViewModel.Feed.HOME -> refreshHome(identity)
                MainViewModel.Feed.EXPLORE -> refreshExplore()
                MainViewModel.Feed.LIBRARY -> refreshLibrary(identity)
            }
            _refreshing.value = _refreshing.value - feed
        }
    }
}
