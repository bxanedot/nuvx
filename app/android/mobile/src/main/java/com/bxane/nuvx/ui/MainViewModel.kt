package com.bxane.nuvx.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bxane.nuvx.R
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.auth.SignedIn
import com.bxane.nuvx.auth.GoogleAccountSession
import com.bxane.nuvx.auth.YouTubeProfile
import com.bxane.nuvx.data.AppUpdateChecker
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.model.TopPick
import kotlinx.coroutines.flow.first
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.navigation.AppNavigator
import com.bxane.nuvx.data.innertube.PlaybackTracker
import com.bxane.nuvx.auth.CapturedSession
import com.bxane.nuvx.auth.WebSessionMode
import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.model.AccountChannel
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.catalogReleases
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.PlaylistPrivacy
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SongMenu
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.SearchHistoryEntity
import com.bxane.nuvx.data.settings.SearchHistory
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.bxane.nuvx.playback.BoundedContextRequests
import com.bxane.nuvx.data.LocalGroup
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists

enum class SearchSource { YOUTUBE, LIBRARY, ADDONS }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    private val _signedIn = SignedIn.state(app)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val homeFeed = MainHomeFeed(
        scope = viewModelScope,
        application = app,
        signedIn = _signedIn,
        listenerKey = ::listenerKey,
        friendly = { it.friendly() },
    )

    val home: StateFlow<UiState<List<HomeShelf>>> = homeFeed.home
    val homeLoadingMore: StateFlow<Boolean> = homeFeed.homeLoadingMore
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = homeFeed.homeRecentlyPlayedLoading
    val topPicks: StateFlow<List<TopPick>?> = homeFeed.topPicks
    val topPicksLoading: StateFlow<Boolean> = homeFeed.topPicksLoading

    private val exploreFeed = MainExploreController(
        viewModelScope,
        { text(it) },
        { it.friendly() },
    )
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = exploreFeed.explore
    val exploreShelves: StateFlow<List<HomeShelf>> = exploreFeed.exploreShelves
    val selectedMoodGenre: StateFlow<MoodGenre?> = exploreFeed.selectedMoodGenre
    val moodGenreShelves: StateFlow<UiState<List<HomeShelf>>> = exploreFeed.moodGenreShelves
    val podcasts: StateFlow<UiState<List<HomeShelf>>> = exploreFeed.podcasts

    private val search = MainSearch(
        scope = viewModelScope,
        application = app,
        text = ::text,
        friendly = { it.friendly() },
    )

    val query: StateFlow<String> = search.query
    val results: StateFlow<UiState<List<SearchResult>>?> = search.results
    val filter: StateFlow<SearchFilter> = search.filter
    val searchLoadingMore: StateFlow<Boolean> = search.searchLoadingMore
    val searchScrollReset: StateFlow<Int> = search.searchScrollReset
    val suggestions: StateFlow<List<String>> = search.suggestions
    val typeaheadResults: StateFlow<List<SearchResult>> = search.typeaheadResults
    val searchSource: StateFlow<SearchSource> = search.searchSource
    val addonResults: StateFlow<UiState<List<Song>>?> = search.addonResults
    val libraryResults: StateFlow<UiState<List<Song>>?> = search.libraryResults

    val addonSearchHistory: StateFlow<List<String>> = SearchHistory.addonRecent

    fun removeAddonSearch(query: String) = SearchHistory.removeAddonQuery(query)

    fun clearAddonSearchHistory() = SearchHistory.clearAddonQueries()

    private val lyricsController = MainLyricsController(
        viewModelScope = viewModelScope,
        getApplication = { getApplication() },
    )
    val lyrics: StateFlow<List<LyricLine>?> = lyricsController.lyrics
    val lyricsSource: StateFlow<LyricsSource?> = lyricsController.lyricsSource
    val lyricsChecked: StateFlow<Boolean> = lyricsController.lyricsChecked
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        lyricsController.lyricsProviderStates

    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) = lyricsController.loadLyrics(videoId, title, artist, durationMs, album, localUri)

    fun selectLyricsProvider(source: LyricsSource) = lyricsController.selectLyricsProvider(source)
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _channels = MutableStateFlow<List<AccountChannel>>(emptyList())
    val channels: StateFlow<List<AccountChannel>> = _channels.asStateFlow()

    private val _channelsLoading = MutableStateFlow(false)
    val channelsLoading: StateFlow<Boolean> = _channelsLoading.asStateFlow()

    private val _selectedChannelKey = MutableStateFlow(
        authStore.activeSession?.activeProfileId ?: authStore.channelPageId ?: authStore.channelDataSyncId,
    )
    val selectedChannelKey: StateFlow<String?> = _selectedChannelKey.asStateFlow()

    private val _selectedChannelName = MutableStateFlow(
        authStore.activeSession?.profiles?.firstOrNull { it.profileId == authStore.activeProfileId }?.name
            ?: authStore.channelName,
    )
    val selectedChannelName: StateFlow<String?> = _selectedChannelName.asStateFlow()

    private val _googleAccounts = MutableStateFlow(authStore.sessions)
    val googleAccounts: StateFlow<List<GoogleAccountSession>> = _googleAccounts.asStateFlow()
    private val _activeAccountId = MutableStateFlow(authStore.activeSession?.accountId)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()
    private val _activeProfileId = MutableStateFlow(authStore.activeProfileId)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()
    private val detailInvalidationController = MainDetailInvalidationController(
        scope = viewModelScope,
        detailStack = _detailStack,
        reloadLocalDetail = ::reloadLocalDetail,
        reloadRemoteDetail = ::reloadRemoteDetail,
    )

    val navigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = ::closeDetail,
        clearDetails = ::clearDetail,
    )

    fun loadReleaseLibrary(browseId: String) = collectionController.loadReleaseLibrary(browseId)

    fun toggleReleaseLibrary(browseId: String) = collectionController.toggleReleaseLibrary(browseId)
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    fun likeStatusOf(videoId: String): LikeStatus = collectionController.likeStatusOf(videoId)

    fun setLike(videoId: String, status: LikeStatus) = collectionController.setLike(videoId, status)

    fun toggleLike(videoId: String) = collectionController.toggleLike(videoId)

    fun toggleDislike(videoId: String): LikeStatus? = collectionController.toggleDislike(videoId)

    fun toggleLibrary(browseId: String) = collectionController.toggleLibrary(browseId)

    fun toggleSubscription(browseId: String) = collectionController.toggleSubscription(browseId)
    private val songMenuController = MainSongMenuController(
        scope = viewModelScope,
        signedIn = { _signedIn.value },
    )
    val songMenu: StateFlow<SongMenu?> = songMenuController.songMenu
    private val feedRefreshController: MainFeedRefreshController = MainFeedRefreshController(
        scope = viewModelScope,
        signedIn = { _signedIn.value },
        home = home,
        library = _library,
        listenerKey = ::listenerKey,
        refreshHome = homeFeed::refreshHome,
        refreshExplore = ::fetchExplore,
        refreshLibrary = ::fetchLibrary,
        refreshHomeRecents = homeFeed::refreshHomeRecents,
        loadPlaylists = ::loadPlaylists,
    )
    private val collectionController = MainCollectionController(
        scope = viewModelScope,
        requireSignIn = ::requireSignIn,
        markLibraryStale = feedRefreshController::markLibraryStale,
        _library = _library,
        _detailStack = _detailStack,
        _songMenu = songMenuController.mutableSongMenu,
    )
    val releaseLibrary: StateFlow<Map<String, LibraryState>> = collectionController.releaseLibrary
    val likeStatuses: StateFlow<Map<String, LikeStatus>> = collectionController.likeStatuses

    fun loadSongMenu(videoId: String?) = songMenuController.loadSongMenu(videoId)

    fun loadPlaylists() = playlistController.loadPlaylists()

    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) = playlistController.addToPlaylists(playlists, song, onResult)

    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) =
        playlistController.createPlaylist(title, privacy, song)

    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) = playlistController.createPlaylistWithVideoIds(title, privacy, videoIds, songs, onResult)

    fun removeFromPlaylist(browseId: String, song: Song) =
        playlistController.removeFromPlaylist(browseId, song)

    fun addSuggestedSong(browseId: String, song: Song) =
        playlistController.addSuggestedSong(browseId, song)

    fun renamePlaylist(playlist: UserPlaylist, title: String) =
        playlistController.renamePlaylist(playlist, title)

    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) =
        playlistController.loadPlaylistEntries(playlist, onResult)

    fun reorderPlaylist(
        playlist: UserPlaylist,
        original: List<Song>,
        reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) = playlistController.reorderPlaylist(playlist, original, reordered, onResult)

    fun deletePlaylist(playlist: UserPlaylist) = playlistController.deletePlaylist(playlist)

    fun editablePlaylist(browseId: String?): UserPlaylist? =
        playlistController.editablePlaylist(browseId)

    private val playlistController = MainPlaylistController(
        scope = viewModelScope,
        authStore = authStore,
        listenerKey = ::listenerKey,
        requireSignIn = ::requireSignIn,
        markLibraryStale = feedRefreshController::markLibraryStale,
        text = ::text,
        withArtwork = { songs, fallback -> songs.withArtwork(fallback) },
        navigator = navigator,
        _library = _library,
        _detailStack = _detailStack,
    )
    val playlists: StateFlow<List<UserPlaylist>> = playlistController.playlists
    val playlistsLoading: StateFlow<Boolean> = playlistController.playlistsLoading
    val playlistOwned: StateFlow<Map<String, Boolean>> = playlistController.playlistOwned

    fun resolvePlaylistOwnership(browseId: String?) = playlistController.resolvePlaylistOwnership(browseId)

    private val libraryHistory = MainLibraryHistoryController(
        scope = viewModelScope,
        isSignedIn = { _signedIn.value },
        listenerKey = ::listenerKey,
        text = ::text,
        friendly = { it.friendly() },
        _library = _library,
        _history = _history,
    )
    private val accountController = MainAccountController(
        scope = viewModelScope,
        authStore = authStore,
        signedIn = _signedIn,
        _account = _account,
        _channels = _channels,
        _channelsLoading = _channelsLoading,
        _selectedChannelKey = _selectedChannelKey,
        _selectedChannelName = _selectedChannelName,
        _googleAccounts = _googleAccounts,
        _activeAccountId = _activeAccountId,
        _activeProfileId = _activeProfileId,
        _library = _library,
        _history = _history,
        _playlists = playlistController.mutablePlaylists,
        _playlistsLoading = playlistController.mutablePlaylistsLoading,
        _playlistOwned = playlistController.mutablePlaylistOwned,
        _songMenu = songMenuController.mutableSongMenu,
        homeFeed = homeFeed,
        libraryHistory = libraryHistory,
        playlistController = playlistController,
    )
    private val detailContextController = MainDetailContextController(
        scope = viewModelScope,
        _detailStack = _detailStack,
        localPageState = ::localPageState,
        withArtwork = { songs, fallback -> songs.withArtwork(fallback) },
        text = ::text,
    )
    private val spotifyPageResolver = MainSpotifyPageResolver(
        scope = viewModelScope,
        _detailStack = _detailStack,
        text = ::text,
    )
    private val detailPageResolver = MainDetailPageResolver(
        application = app,
        scope = viewModelScope,
        _detailStack = _detailStack,
        browseTypeOf = { browseId, fallback -> browseTypeOf(browseId, fallback) },
        downloadedCopyOf = ::downloadedCopyOf,
        isRemoteLibrary = { browseId -> remoteLibrary(browseId) != null },
        localPageState = ::localPageState,
        withArtwork = { songs, fallback -> songs.withArtwork(fallback) },
        setPlaylistOwned = playlistController::setPlaylistOwned,
        fillIn = { browseId, token, fallback -> detailContextController.fillIn(browseId, token, fallback) },
        openSpotifyPage = { browseId, title, subtitle, thumbnailUrl ->
            spotifyPageResolver.openPage(browseId, title, subtitle, thumbnailUrl)
        },
        text = ::text,
        friendly = { it.friendly() },
    )

    private fun requireSignIn(): Boolean = _signedIn.value

    fun onLibraryShown() = feedRefreshController.onLibraryShown()

    init {
        search.start()
        loadHome()
        if (_signedIn.value) {
            loadLibrary()
            accountController.loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            PlaybackTracker.registeredPlays.drop(1).collect { feedRefreshController.markHomeStale() }
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
        detailInvalidationController.start()
        viewModelScope.launch {
            AppUpdateChecker.clearCache(getApplication())
            AppUpdateChecker.check()
        }
    }

    fun onHomeShown() = feedRefreshController.onHomeShown()

    enum class Feed { HOME, EXPLORE, LIBRARY }

    val refreshing: StateFlow<Set<Feed>> = feedRefreshController.refreshing

    fun refresh(feed: Feed) = feedRefreshController.refresh(feed)

    fun loadExplore() = exploreFeed.loadExplore()

    private suspend fun fetchExplore() = exploreFeed.fetchExplore()

    fun onExploreShown() = exploreFeed.onExploreShown()

    fun openMoodGenre(item: MoodGenre) = exploreFeed.openMoodGenre(item)

    fun loadPodcasts(force: Boolean = false) = exploreFeed.loadPodcasts(force)

    fun closeMoodGenre(): Boolean = exploreFeed.closeMoodGenre()

    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

    fun loadHome() = homeFeed.loadHome()

    fun refreshTopPicksIfStale() = homeFeed.refreshTopPicksIfStale()

    fun loadMoreHome() = homeFeed.loadMoreHome()

    fun loadLibrary() = libraryHistory.loadLibrary()

    private suspend fun fetchLibrary(identity: String?) = libraryHistory.fetchLibrary(identity)

    fun loadHistory() = libraryHistory.loadHistory()

    fun removeFromHistory(
        token: String,
        onSuccess: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) = libraryHistory.removeFromHistory(token, onSuccess, onFailure)

    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    fun recordSearch() = search.recordSearch()

    fun searchFor(term: String) = search.searchFor(term)

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) = search.onQueryChange(newValue)

    fun submitSearch() = search.submitSearch()

    fun setSearchSource(source: SearchSource) = search.setSearchSource(source)

    fun loadLibrarySongs() = search.loadLibrarySongs()

    fun onFilterChange(value: SearchFilter) = search.onFilterChange(value)

    fun loadMoreSearchResults() = search.loadMoreSearchResults()

    companion object {
        const val SUGGEST_DEBOUNCE_MS = 180L

        const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 350L

        const val TYPEAHEAD_MAX_RESULTS = 15

        const val SEARCH_CACHE_ENTRIES = 100

        const val SOURCE_SEARCH_TIMEOUT_MS = 4000L

        const val SOURCE_SEARCH_LIMIT = 12

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            browseId.startsWith(Downloads.PLAYLIST_PREFIX) ->
                Downloads.recordIdOf(browseId)
                    ?.let { Downloads.collections.value[it] }
                    ?.takeIf { !it.playlist }
                    ?.let { BrowseType.ALBUM }
                    ?: BrowseType.PLAYLIST
            browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
            browseId.startsWith(OfflinePlaylists.PREFIX) -> BrowseType.PLAYLIST
            browseId.startsWith("UC") -> BrowseType.ARTIST
            browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    private data class RemoteLibrary(
        val emptyRes: Int,
        val songs: suspend () -> List<Song>,
    )

    private fun remoteLibrary(browseId: String): RemoteLibrary? = when (browseId) {
        com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.webdav_empty,
                com.bxane.nuvx.data.webdav.WebDavRepository::getSongs,
            )
        com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.smb_empty,
                com.bxane.nuvx.data.smb.SmbRepository::getSongs,
            )
        else -> null
    }

    private suspend fun remoteSongsState(remote: RemoteLibrary): UiState<List<Song>> =
        com.bxane.nuvx.data.remote.RemoteListing.state(runCatching { remote.songs() }, text(remote.emptyRes))

    private fun reloadRemoteDetail(browseId: String) {
        if (_detailStack.value.any { page -> page.browseId == browseId }) {
            reloadLocalDetail(browseId)
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) = detailPageResolver.openDetail(browseId, title, subtitle, thumbnailUrl, type)

    fun openLocalGroup(group: LocalGroup) {
        if (_detailStack.value.lastOrNull()?.browseId == group.browseId) return
        _detailStack.value += DetailPage(
            browseId = group.browseId,
            title = group.title,
            subtitle = group.subtitle,
            thumbnailUrl = group.thumbnailUrl,
            songs = if (group.songs.isEmpty()) UiState.Error(text(R.string.no_tracks_here)) else UiState.Success(group.songs),
            type = group.type,
        )
    }

    fun retitleDetail(browseId: String, title: String) {
        if (title.isBlank()) return
        _detailStack.value = _detailStack.value.map { if (it.browseId == browseId) it.copy(title = title) else it }
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    private val localPageResolver = MainLocalPageResolver(
        application = getApplication(),
        text = ::text,
        remotePageState = { browseId -> remoteLibrary(browseId)?.let { remoteSongsState(it) } },
    )

    private suspend fun localPageState(browseId: String): UiState<List<Song>>? =
        localPageResolver.localPageState(browseId)

    private suspend fun downloadedCopyOf(browseId: String): List<Song> =
        localPageResolver.downloadedCopyOf(browseId)
    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) =
        detailContextController.applyBoundedContextSnapshot(snapshot)

    fun takePlaybackContext(browseId: String): DetailPage? =
        detailContextController.takePlaybackContext(browseId)

    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) = detailContextController.collectSongs(browseId, artworkFallback, onResult)

    private val catalogController = MainCatalogController(
        scope = viewModelScope,
        withArtwork = { songs, fallback -> songs.withArtwork(fallback) },
    )
    val catalogLoading: StateFlow<Set<String>> = catalogController.catalogLoading

    fun collectCatalog(page: DetailPage, onResult: (List<Pair<ShelfItem, List<Song>>>) -> Unit) =
        catalogController.collectCatalog(page, onResult)
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }

    fun loadChannels(force: Boolean = false) = accountController.loadChannels(force)

    fun selectChannel(channel: AccountChannel) = accountController.selectChannel(channel)

    fun onWebSession(
        session: CapturedSession,
        mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) = accountController.onWebSession(session, mode, onComplete)

    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) =
        accountController.selectProfile(accountId, selectedProfileId, supplied)

    fun stepProfile(forward: Boolean): Boolean = accountController.stepProfile(forward)

    fun removeAccount(accountId: String) = accountController.removeAccount(accountId)

    private fun listenerKey(): String? = accountController.listenerKey()

    fun signOut() = accountController.signOut()

    private fun Throwable.friendly(): String = when {
        message?.contains("resolve host", true) == true ||
            message?.contains("Unable to resolve", true) == true -> text(R.string.no_internet_connection)
        message?.contains("401") == true || message?.contains("403") == true ->
            text(R.string.youtube_request_rejected)
        else -> message ?: text(R.string.something_went_wrong)
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}

internal fun lyricsLookupKey(
    videoId: String,
    sources: Set<LyricsSource>,
    order: List<LyricsSource>,
    prioritizeSyllableSync: Boolean,
): List<Any> = listOf(videoId, sources, order, prioritizeSyllableSync)
