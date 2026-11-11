package com.bxane.nuvx.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bxane.nuvx.R
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.auth.SignedIn
import com.bxane.nuvx.auth.GoogleAccountSession
import com.bxane.nuvx.auth.YouTubeProfile
import com.bxane.nuvx.auth.profileId
import com.bxane.nuvx.auth.sessionId
import com.bxane.nuvx.auth.adjacentProfile
import com.bxane.nuvx.data.AppUpdateChecker
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.TopPick
import kotlinx.coroutines.flow.first
import com.bxane.nuvx.data.model.SPOTIFY_MISSING_PREFIX
import com.bxane.nuvx.data.model.SPOTIFY_PENDING_PREFIX
import com.bxane.nuvx.data.spotify.SPOTIFY_PAGE_PREFIX
import com.bxane.nuvx.data.spotify.SpotifyImporter
import com.bxane.nuvx.data.spotify.SpotifyLibrary
import com.bxane.nuvx.data.spotify.SpotifyTrack
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.navigation.AppNavigator
import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.PlaybackTracker
import com.bxane.nuvx.data.innertube.StreamResolver
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
import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.SearchHistoryEntity
import com.bxane.nuvx.data.settings.SearchHistory
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.playback.BoundedContextRequests
import com.bxane.nuvx.playback.contextEntryKey
import com.bxane.nuvx.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.bxane.nuvx.data.LocalGroup
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import java.util.Locale

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

    private data class ListenerSnapshot(
        val account: Account?, val library: UiState<LibraryPage>,
        val history: UiState<List<Song>>, val playlists: List<UserPlaylist>,
        val owned: Map<String, Boolean>,
    )
    private val listenerCache = mutableMapOf<String, ListenerSnapshot>()

    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    val navigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = ::closeDetail,
        clearDetails = ::clearDetail,
    )

    private val fillInJobs = mutableMapOf<String, Job>()

    fun loadReleaseLibrary(browseId: String) = collectionController.loadReleaseLibrary(browseId)

    fun toggleReleaseLibrary(browseId: String) = collectionController.toggleReleaseLibrary(browseId)
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    fun likeStatusOf(videoId: String): LikeStatus = collectionController.likeStatusOf(videoId)

    fun setLike(videoId: String, status: LikeStatus) = collectionController.setLike(videoId, status)

    fun toggleLike(videoId: String) = collectionController.toggleLike(videoId)

    fun toggleDislike(videoId: String): LikeStatus? = collectionController.toggleDislike(videoId)

    fun toggleLibrary(browseId: String) = collectionController.toggleLibrary(browseId)

    fun toggleSubscription(browseId: String) = collectionController.toggleSubscription(browseId)
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()
    private val collectionController = MainCollectionController(
        scope = viewModelScope,
        requireSignIn = ::requireSignIn,
        markLibraryStale = { libraryStale = true },
        _library = _library,
        _detailStack = _detailStack,
        _songMenu = _songMenu,
    )
    val releaseLibrary: StateFlow<Map<String, LibraryState>> = collectionController.releaseLibrary
    val likeStatuses: StateFlow<Map<String, LikeStatus>> = collectionController.likeStatuses

    private var songMenuJob: Job? = null


    fun loadSongMenu(videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
        if (videoId == null || !_signedIn.value) return
        songMenuJob = viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return@launch
            _songMenu.value = menu
            LikeState.rememberStated(videoId, menu.likeStatus)
        }
    }

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

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

    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()
    private val playlistController = MainPlaylistController(
        scope = viewModelScope,
        authStore = authStore,
        listenerKey = ::listenerKey,
        requireSignIn = ::requireSignIn,
        markLibraryStale = { libraryStale = true },
        onPlaylistOwned = ::setPlaylistOwned,
        text = ::text,
        withArtwork = { songs, fallback -> songs.withArtwork(fallback) },
        navigator = navigator,
        _playlists = _playlists,
        _playlistsLoading = _playlistsLoading,
        _playlistOwned = _playlistOwned,
        _library = _library,
        _detailStack = _detailStack,
    )

    fun resolvePlaylistOwnership(browseId: String?) = playlistController.resolvePlaylistOwnership(browseId)

    private fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }


    private fun requireSignIn(): Boolean = _signedIn.value

    private var libraryStale = false

    fun onLibraryShown() {
        loadPlaylists()
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    init {
        search.start()
        loadHome()
        if (_signedIn.value) {
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            PlaybackTracker.registeredPlays.drop(1).collect { homeStale = true }
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
        viewModelScope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
        viewModelScope.launch {
            AudioCache.contentsChanged.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == CACHE_FOLDER_BROWSE_ID }) {
                    reloadLocalDetail(CACHE_FOLDER_BROWSE_ID)
                }
            }
        }
        viewModelScope.launch {
            AppSettings.webdavUrl.drop(1).collect {
                reloadRemoteDetail(com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
        viewModelScope.launch {
            combine(
                AppSettings.smbHost,
                AppSettings.smbShare,
                AppSettings.smbBasePath,
                AppSettings.smbUsername,
                AppSettings.smbPassword,
            ) { fields -> fields.toList() }
                .drop(1)
                .debounce(300)
                .collect { reloadRemoteDetail(com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID) }
        }
        viewModelScope.launch {
            AppUpdateChecker.clearCache(getApplication())
            AppUpdateChecker.check()
        }
    }

    private var homeStale = false

    fun onHomeShown() {
        if (!homeStale) return
        homeStale = false
        if (home.value is UiState.Success) homeFeed.refreshHomeRecents()
    }

    private fun loadAccount() {
        val identity = listenerKey()
        viewModelScope.launch {
            val account = YtMusicRepository.account().getOrNull()
            if (identity != listenerKey()) return@launch
            _account.value = account
            if (account != null && _selectedChannelKey.value != null) {
                _selectedChannelName.value = account.name
                val accountId = _activeAccountId.value
                val profileId = _activeProfileId.value
                val session = accountId?.let { id -> authStore.sessions.firstOrNull { it.accountId == id } }
                val updated = session?.let { saved -> saved.copy(
                    name = account.name, email = account.email,
                    profiles = saved.profiles.map { profile ->
                        if (profile.profileId == profileId) profile.copy(
                            name = account.name, handle = account.email, avatar = account.thumbnailUrl,
                        ) else profile
                    },
                ) }
                if (updated != null) {
                    authStore.upsertSession(updated, activate = false)
                    _googleAccounts.value = authStore.sessions
                }
            }
        }
    }

    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        if (feed == Feed.LIBRARY && !_signedIn.value) return
        val identity = listenerKey()
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            when (feed) {
                Feed.HOME -> homeFeed.refreshHome(identity)
                Feed.EXPLORE -> fetchExplore()
                Feed.LIBRARY -> fetchLibrary(identity)
            }
            _refreshing.value = _refreshing.value - feed
        }
    }

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

    private val libraryHistory = MainLibraryHistoryController(
        scope = viewModelScope,
        isSignedIn = { _signedIn.value },
        listenerKey = ::listenerKey,
        text = ::text,
        friendly = { it.friendly() },
        _library = _library,
        _history = _history,
    )
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
        private const val SPOTIFY_MATCH_PARALLELISM = 6

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
    ) {
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            openSpotifyPage(browseId, title, subtitle, thumbnailUrl)
            return
        }
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            var artwork: String? = null
            var name: String? = null
            var credit: String? = null
            var more: String? = null
            var suggested: List<Song> = emptyList()
            var library: LibraryState? = null
            var description: String? = null
            var subscriberCountText: String? = null
            var monthlyListenerCount: String? = null
            var subscription: SubscriptionState? = null
            val localPlaylist = com.bxane.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(browseId)
            val remote = remoteLibrary(browseId)
            val onDevice = if (localPlaylist == null && remote == null && !browseId.startsWith("local:")) {
                downloadedCopyOf(browseId)
            } else {
                emptyList()
            }
            if (onDevice.isNotEmpty()) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId && it.songs is UiState.Loading) it.copy(songs = UiState.Success(onDevice)) else it
                }
            }
            if (localPlaylist != null) {
                name = localPlaylist.title
                credit = getApplication<Application>().getString(R.string.local_playlist_subtitle, localPlaylist.songs.size)
                artwork = localPlaylist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            }
            OfflinePlaylists.get(browseId)?.let { name = it.title }
            val local = localPageState(browseId)
            if (local is UiState.Success) {
                val record = Downloads.recordIdOf(browseId)?.let { Downloads.collections.value[it] }
                val group = LocalGroups.groupOf(
                    browseId,
                    local.data,
                    Downloads.collections.value.values.filter { it.artist }
                        .mapNotNull { c -> c.thumbnailUrl?.let { c.title.lowercase(Locale.ROOT) to it } }.toMap(),
                )
                if (thumbnailUrl == null) artwork = record?.thumbnailUrl ?: group?.thumbnailUrl
                if (subtitle.isBlank()) credit = (record?.subtitle ?: group?.subtitle)?.ifBlank { null }
            }
            val state = when {
                local != null -> local
                resolved == BrowseType.ARTIST -> {
                    YtMusicRepository.artistPage(browseId).fold(
                        onSuccess = { page ->
                            sections = page.sections
                            artwork = page.thumbnailUrl
                            name = page.name
                            description = page.description
                            subscriberCountText = page.subscriberCountText
                            monthlyListenerCount = page.monthlyListenerCount
                            subscription = page.subscription
                            if (page.songs.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
                else -> {
                    YtMusicRepository.browseSongs(browseId).fold(
                        onSuccess = { page ->
                            page.owned?.let { setPlaylistOwned(browseId, it) }
                            page.header?.let { header ->
                                if (title.isBlank()) name = header.title
                                if (resolved == BrowseType.ALBUM && header.subtitle.isNotBlank()) {
                                    credit = header.subtitle
                                } else if (subtitle.isBlank()) {
                                    credit = header.subtitle
                                }
                                if (thumbnailUrl == null) artwork = header.thumbnailUrl
                            }
                            description = page.description
                            if (page.songs.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                more = page.continuation
                                suggested = page.suggested.withArtwork(thumbnailUrl ?: artwork)
                                library = page.library
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
            }
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId &&
                    (it.songs is UiState.Loading || (onDevice.isNotEmpty() && state is UiState.Success))
                ) {
                    it.copy(
                        songs = state,
                        sections = sections,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name ?: it.title,
                        subtitle = credit ?: it.subtitle,
                        suggestedSongs = suggested,
                        continuation = more,
                        library = library,
                        description = description,
                        subscriberCountText = subscriberCountText,
                        monthlyListenerCount = monthlyListenerCount,
                        subscription = subscription,
                    )
                } else {
                    it
                }
            }
            more?.let { fillIn(browseId, it, thumbnailUrl ?: artwork) }
        }
    }

    private fun openSpotifyPage(browseId: String, title: String, subtitle: String, thumbnailUrl: String?) {
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = BrowseType.PLAYLIST,
        )
        viewModelScope.launch {
            val playlistId = browseId.removePrefix(SPOTIFY_PAGE_PREFIX)
            fun open() = _detailStack.value.any { it.browseId == browseId }
            fun setSongs(songs: UiState<List<Song>>) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(songs = songs) else it
                }
            }
            launch {
                val cover = runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull() ?: return@launch
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(thumbnailUrl = cover) else it
                }
            }
            val tracks = runCatching {
                SpotifyLibrary.tracks(playlistId) { soFar ->
                    setSongs(UiState.Success(soFar.map { it.asPendingSong() }))
                }
            }.getOrElse {
                setSongs(UiState.Error(it.message ?: text(R.string.failed)))
                return@launch
            }
            if (tracks.isEmpty()) {
                setSongs(UiState.Error(text(R.string.spotify_empty_tracks)))
                return@launch
            }
            val gate = Semaphore(SPOTIFY_MATCH_PARALLELISM)
            coroutineScope {
                tracks.forEachIndexed { index, track ->
                    launch {
                        gate.withPermit {
                            if (!open()) return@withPermit
                            val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                            val found = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                                ?: track.asPendingSong().let {
                                    it.copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                                }
                            _detailStack.value = _detailStack.value.map { page ->
                                val list = (page.songs as? UiState.Success<List<Song>>)?.data
                                if (page.browseId == browseId && list != null && index < list.size) {
                                    page.copy(songs = UiState.Success(list.toMutableList().also { it[index] = found }))
                                } else page
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SpotifyTrack.asPendingSong() = Song(
        videoId = SPOTIFY_PENDING_PREFIX + id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { ms ->
            "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        },
        albumName = album,
    )

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

    private suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = getApplication<Application>()
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        if (OfflinePlaylists.isOfflinePlaylist(browseId)) {
            val playlist = OfflinePlaylists.get(browseId) ?: return UiState.Error(text(R.string.no_tracks_here))
            return nonEmpty(LocalMediaRepository.present(context, playlist.songs)) {
                text(if (playlist.isLiked) R.string.offline_liked_empty else R.string.offline_playlist_empty)
            }
        }
        com.bxane.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        remoteLibrary(browseId)?.let { return remoteSongsState(it) }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            Downloads.recordIdOf(browseId) != null ->
                nonEmpty(downloadedPlaylist(browseId)) { text(R.string.downloaded_playlist_empty) }
            browseId == "local:downloads" ->
                nonEmpty(Downloads.getDownloadedSongs(context)) { "No downloaded tracks" }
            browseId == CACHE_FOLDER_BROWSE_ID -> cachedSongsState()
            browseId == "local:all" -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                nonEmpty(LocalMediaRepository.getLocalMusic(context)) { text(R.string.no_local_audio_found) }
            }
            else -> null
        }
    }

    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    private suspend fun downloadedCopyOf(browseId: String): List<Song> {
        val bare = browseId.removePrefix("VL")
        val id = Downloads.collections.value.keys.firstOrNull { it.removePrefix("VL") == bare } ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    private suspend fun cachedSongsState(): UiState<List<Song>> {
        val songs = AudioCache.cachedSongs().map { it.song }
        return if (songs.isEmpty()) UiState.Error(text(R.string.cached_songs_empty)) else UiState.Success(songs)
    }

    private fun fillIn(browseId: String, token: String, artworkFallback: String?) {
        fillInJobs[browseId]?.cancel()
        fillInJobs[browseId] = viewModelScope.launch {
            delay(150)
            var next: String? = token
            while (next != null) {
                val fetched = YtMusicRepository.moreSongs(next).getOrNull() ?: return@launch
                currentCoroutineContext().ensureActive()
                val stack = _detailStack.value
                if (stack.none { it.browseId == browseId }) return@launch
                var progressed = false
                val updated = stack.map { current ->
                    if (current.browseId != browseId) return@map current
                    val existing = (current.songs as? UiState.Success)?.data ?: return@map current
                    val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
                    val added = fetched.songs
                        .filter { known.add(it.contextEntryKey()) }
                        .withArtwork(artworkFallback)
                    val knownVideoIds = (existing + added).mapTo(HashSet()) { it.videoId }
                    val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                    val addedSuggested = fetched.suggested
                        .filter { it.videoId !in knownVideoIds && knownSuggested.add(it.videoId) }
                        .withArtwork(artworkFallback)
                    if (added.isEmpty() && addedSuggested.isEmpty()) return@map current
                    progressed = true
                    current.copy(
                        songs = UiState.Success(existing + added),
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                        continuation = fetched.continuation,
                    )
                }
                if (!progressed) return@launch
                _detailStack.value = updated
                next = fetched.continuation
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (fillInJobs[browseId] === job) fillInJobs.remove(browseId)
            }
        }
    }

    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val browseId = snapshot.request.source.id ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return
        val page = stack[index]
        if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
        if (snapshot.serviceOwned) {
            if (snapshot.continuationToken != null) fillInJobs.remove(browseId)?.cancel()
        } else if (snapshot.released && snapshot.continuationToken != null &&
            fillInJobs[browseId]?.isActive != true
        ) {
            fillIn(browseId, snapshot.continuationToken, page.thumbnailUrl)
        }
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .withArtwork(page.thumbnailUrl)
        val continuation = if (snapshot.songs.size >= existing.size) snapshot.continuationToken else page.continuation
        if (added.isEmpty() && page.continuation == continuation) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(
                songs = UiState.Success(existing + added),
                continuation = continuation,
            )
        }
    }

    fun takePlaybackContext(browseId: String): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.request.source.id == browseId }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

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
    ) {
        viewModelScope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> YtMusicRepository.allSongs(browseId)
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    private val _catalogLoading = MutableStateFlow<Set<String>>(emptySet())

    val catalogLoading: StateFlow<Set<String>> = _catalogLoading.asStateFlow()

    fun collectCatalog(page: DetailPage, onResult: (List<Pair<ShelfItem, List<Song>>>) -> Unit) {
        if (page.browseId in _catalogLoading.value) return
        _catalogLoading.update { it + page.browseId }
        viewModelScope.launch {
            val gate = Semaphore(4)
            val releases = try {
                page.sections.catalogReleases().map { release ->
                    async {
                        gate.withPermit {
                            YtMusicRepository.allSongs(release.browseId!!).getOrNull()
                                ?.map { it.copy(albumName = it.albumName ?: release.title) }
                                ?.withArtwork(release.thumbnailUrl)
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { release to it }
                        }
                    }
                }.awaitAll().filterNotNull()
            } finally {
                _catalogLoading.update { it - page.browseId }
            }
            onResult(releases)
        }
    }

    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }

    fun loadChannels(force: Boolean = false) {
        if (!_signedIn.value) return
        if (_channelsLoading.value) return
        if (!force && _channels.value.isNotEmpty()) return
        viewModelScope.launch {
            _channelsLoading.value = true
            YtMusicRepository.accountChannels()
                .onSuccess {
                    _channels.value = it
                    persistDetectedProfiles(it)
                }
            _channelsLoading.value = false
        }
    }

    fun selectChannel(channel: AccountChannel) {
        val accountId = _activeAccountId.value ?: return
        val profile = YouTubeProfile(
            profileId = profileId(channel.pageId, channel.dataSyncId, channel.name),
            name = channel.name, handle = channel.subtitle, avatar = channel.thumbnailUrl,
            pageId = channel.pageId, dataSyncId = channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        )
        selectProfile(accountId, profile.profileId, profile)
    }

    fun onWebSession(
        session: CapturedSession,
        mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!session.loggedIn) {
            onComplete(false)
            return
        }
        val oldAccountId = _activeAccountId.value
        val oldProfileId = _activeProfileId.value
        viewModelScope.launch {
            Innertube.cookie = session.cookie
            Innertube.adoptSessionScope(
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                visitorData = session.visitorData,
                clientVersion = session.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            }
            if (account == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }

            val accountId = when (mode) {
                WebSessionMode.SWITCH_CHANNEL -> oldAccountId
                WebSessionMode.SIGN_IN -> authStore.sessions.firstOrNull {
                    account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
                }?.accountId ?: sessionId(session.cookie, null)
            }
            if (accountId == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }
            val previous = authStore.sessions.firstOrNull { it.accountId == accountId }
            val selectedProfileId = profileId(session.pageId, session.dataSyncId, account.name)
            val profile = YouTubeProfile(
                profileId = selectedProfileId,
                name = account.name,
                handle = account.email,
                avatar = account.thumbnailUrl,
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                isBrandAccount = session.pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = previous?.profiles.orEmpty().filterNot { known ->
                known.profileId == profile.profileId ||
                    (hasRealIdentity && known.profileId.startsWith("profile:"))
            } + profile
            val stored = GoogleAccountSession(
                accountId = accountId,
                cookie = session.cookie,
                name = account.name,
                email = account.email,
                profiles = profiles,
                activeProfileId = profile.profileId,
            )

            val wasSignedIn = _signedIn.value
            if (wasSignedIn) cacheCurrentListener()
            authStore.upsertSession(stored)
            authStore.cookie = session.cookie
            _googleAccounts.value = authStore.sessions
            _activeAccountId.value = accountId
            _activeProfileId.value = profile.profileId
            _selectedChannelKey.value = profile.profileId
            _selectedChannelName.value = account.name
            _account.value = account
            _channels.value = emptyList()
            _signedIn.value = true

            StreamResolver.onSessionChanged()
            if (wasSignedIn) clearListenerState()
            reloadForAccount()
            loadChannels(force = true)
            onComplete(true)
        }
    }

    private fun restoreActiveSession(accountId: String?, selectedProfileId: String?) {
        val account = authStore.sessions.firstOrNull { it.accountId == accountId }
        val profile = account?.profiles?.firstOrNull { it.profileId == selectedProfileId }
        Innertube.cookie = account?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) {
        val source = _googleAccounts.value.firstOrNull { it.accountId == accountId } ?: return
        val profile = supplied ?: source.profiles.firstOrNull { it.profileId == selectedProfileId } ?: return
        if (accountId == _activeAccountId.value && profile.profileId == _activeProfileId.value) return
        cacheCurrentListener()
        val index = source.profiles.indexOfFirst { it.profileId == profile.profileId }
        val updatedProfiles = if (index >= 0) {
            source.profiles.toMutableList().apply { set(index, profile) }
        } else {
            source.profiles + profile
        }
        val updated = source.copy(
            profiles = updatedProfiles,
            activeProfileId = profile.profileId,
        )
        authStore.upsertSession(updated)
        authStore.select(accountId, profile.profileId)
        _googleAccounts.value = authStore.sessions
        _activeAccountId.value = accountId
        _activeProfileId.value = profile.profileId
        authStore.cookie = updated.cookie
        Innertube.cookie = updated.cookie
        Innertube.selectChannel(profile.pageId, profile.dataSyncId, profile.authUser)
        _selectedChannelKey.value = profile.profileId
        _selectedChannelName.value = profile.name
        StreamResolver.onSessionChanged()
        clearListenerState(restoreCached = true)
        reloadForAccount()
    }

    fun stepProfile(forward: Boolean): Boolean {
        val target = adjacentProfile(_googleAccounts.value, _activeAccountId.value, _activeProfileId.value, forward)
            ?: return false
        selectProfile(target.first, target.second)
        return true
    }

    fun removeAccount(accountId: String) {
        val fallback = authStore.removeAccount(accountId)
        _googleAccounts.value = authStore.sessions
        if (fallback == null) { signOut(); return }
        selectProfile(fallback.accountId, fallback.activeProfileId ?: fallback.profiles.firstOrNull()?.profileId ?: return)
    }

    private fun persistDetectedProfiles(channels: List<AccountChannel>) {
        val accountId = _activeAccountId.value ?: return
        val current = authStore.sessions.firstOrNull { it.accountId == accountId } ?: return
        val detected = channels.map { channel -> YouTubeProfile(
            profileId(channel.pageId, channel.dataSyncId, channel.name), channel.name, channel.subtitle,
            channel.thumbnailUrl, channel.pageId, channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        ) }
        val stalePlaceholder = if (detected.isEmpty()) emptySet() else current.profiles
            .filter { it.profileId.startsWith("profile:") }
            .map { it.profileId }
            .toSet()
        val profiles = (current.profiles.filter { known ->
            known.profileId !in stalePlaceholder && detected.none { it.profileId == known.profileId }
        } + detected)
        val selected = when {
            current.activeProfileId != null && current.activeProfileId !in stalePlaceholder -> current.activeProfileId
            current.activeProfileId in stalePlaceholder -> detected.singleOrNull()?.profileId
            else -> null
        } ?: profiles.firstOrNull()?.profileId
        authStore.upsertSession(current.copy(profiles = profiles, activeProfileId = selected), activate = false)
        _googleAccounts.value = authStore.sessions
    }

    private fun cacheCurrentListener() {
        val key = listenerKey() ?: return
        listenerCache[key] = ListenerSnapshot(
            _account.value, _library.value, _history.value, _playlists.value, _playlistOwned.value,
        )
    }

    private fun listenerKey(): String? = _activeAccountId.value?.let { accountId ->
        _activeProfileId.value?.let { profileId -> "$accountId:$profileId" }
    }

    private fun clearListenerState(restoreCached: Boolean = false) {
        _account.value = null
        libraryHistory.cancelLikedSync()
        LikeState.clear()
        _playlistsLoading.value = false
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        playlistController.clearOwnershipInFlight()
        _songMenu.value = null
        _library.value = UiState.Loading
        _history.value = UiState.Loading
        if (restoreCached) listenerCache[listenerKey()]?.let { cached ->
            _account.value = cached.account
            _library.value = cached.library
            _history.value = cached.history
            _playlists.value = cached.playlists
            _playlistOwned.value = cached.owned
        }
    }

    private fun reloadForAccount() {
        viewModelScope.launch {
            Innertube.ensureSessionScope()
            loadHome()
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
    }

    fun signOut() {
        val current = _activeAccountId.value
        if (current != null && authStore.sessions.size > 1) {
            removeAccount(current)
            return
        }
        authStore.signOut()
        Innertube.cookie = null
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
        Innertube.selectChannel(null, null)
        _channels.value = emptyList()
        _selectedChannelKey.value = null
        _selectedChannelName.value = null
        _googleAccounts.value = emptyList()
        _activeAccountId.value = null
        _activeProfileId.value = null
        _library.value = UiState.Loading
        libraryHistory.cancelLikedSync()
        LikeState.clear()
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        playlistController.clearOwnershipInFlight()
        _songMenu.value = null
        loadHome()
    }

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
