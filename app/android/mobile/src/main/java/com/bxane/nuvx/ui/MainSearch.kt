package com.bxane.nuvx.ui

import android.app.Application
import android.util.LruCache
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.matchesSearch
import com.bxane.nuvx.data.model.EntityType
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchHistoryEntity
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.settings.SearchHistory
import com.bxane.nuvx.data.sources.AddonSource
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.playback.DirectSourceGate
import com.bxane.nuvx.playback.StreamChoice
import com.bxane.nuvx.ui.MainViewModel.Companion.SEARCH_CACHE_ENTRIES
import com.bxane.nuvx.ui.MainViewModel.Companion.SUGGEST_DEBOUNCE_MS
import com.bxane.nuvx.ui.MainViewModel.Companion.TYPEAHEAD_MAX_RESULTS
import com.bxane.nuvx.ui.MainViewModel.Companion.TYPEAHEAD_MEDIA_DEBOUNCE_MS
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class MainSearch(
    private val scope: CoroutineScope,
    private val application: Application,
    private val text: (Int) -> String,
    private val friendly: (Throwable) -> String,
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    private var searchSubmitted = false

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    private val _searchSource = MutableStateFlow(SearchSource.YOUTUBE)
    val searchSource: StateFlow<SearchSource> = _searchSource.asStateFlow()

    private val _addonResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val addonResults: StateFlow<UiState<List<Song>>?> = _addonResults.asStateFlow()
    private var addonSearchJob: Job? = null

    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val newestRequestId = AtomicLong(0L)

    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    private val searchCache = LruCache<String, SearchCacheEntry>(SEARCH_CACHE_ENTRIES)
    private var searchSession: SearchSession? = null

    fun start() {
        startSearchPipeline()
        startSuggestPipeline()
        startTypeaheadMediaPipeline()
    }

    fun recordSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        val fromTypeahead = _typeaheadResults.value.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (fromTypeahead is SearchResult.Track) {
            SearchHistory.record(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        if (fromTypeahead is SearchResult.TopTrack) {
            SearchHistory.record(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        val topResult = (_results.value as? UiState.Success)?.data?.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (topResult is SearchResult.Track) {
            SearchHistory.record(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else if (topResult is SearchResult.TopTrack) {
            SearchHistory.record(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else {
            SearchHistory.record(SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ))
        }
    }

    fun searchFor(term: String) {
        _query.value = term
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        runSearch(anonymous = true)
    }

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            searchSubmitted = false
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            _results.value = null
            addonSearchJob?.cancel()
            _addonResults.value = null
            _searchScrollReset.value += 1
            return
        }
        searchSubmitted = false
        if (_searchSource.value != SearchSource.YOUTUBE) return
        suggestRequests.tryEmit(newValue)
    }

    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty() || _searchSource.value == SearchSource.LIBRARY) return
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        if (_searchSource.value == SearchSource.ADDONS) {
            runAddonSearch(q)
            return
        }
        runSearch(anonymous = false)
    }

    private fun runAddonSearch(q: String) {
        SearchHistory.recordAddonQuery(q)
        addonSearchJob?.cancel()
        _searchScrollReset.value += 1
        val addons = SourceRegistry.active().filterIsInstance<AddonSource>()
        if (addons.isEmpty()) {
            _addonResults.value = UiState.Error(text(R.string.search_addons_none))
            return
        }
        _addonResults.value = UiState.Loading
        addonSearchJob = scope.launch {
            val perAddon = coroutineScope {
                addons.map { addon ->
                    async {
                        withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                            runCatching { addon.search(q, limit = ADDON_SEARCH_LIMIT) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }.map { it.await() }
            }
            val merged = buildList {
                val longest = perAddon.maxOfOrNull { it.size } ?: 0
                for (i in 0 until longest) perAddon.forEach { rows -> rows.getOrNull(i)?.let(::add) }
            }
            _addonResults.value = UiState.Success(merged)
        }
    }

    fun setSearchSource(source: SearchSource) {
        if (_searchSource.value == source) return
        _searchSource.value = source
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        _searchScrollReset.value += 1
        when (source) {
            SearchSource.LIBRARY -> loadLibrarySongs()
            SearchSource.YOUTUBE -> submitSearch()
            SearchSource.ADDONS -> if (_query.value.isNotBlank()) submitSearch()
        }
    }

    fun loadLibrarySongs() {
        scope.launch {
            val context = application
            _librarySongs.value = if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.fold(
                    onSuccess = { songs ->
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    },
                    onFailure = { UiState.Error(text(R.string.no_local_audio_found)) },
                )
            }
        }
    }

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        searchSubmitted = true
        runSearch()
    }

    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
        val anonymous: Boolean = true,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    private fun runSearch(anonymous: Boolean = true) {
        val query = _query.value
        if (query.isBlank()) {
            newestRequestId.incrementAndGet()
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        _searchScrollReset.value += 1
        searchSession = null
        _searchLoadingMore.value = false
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id, anonymous))
    }

    private fun startSearchPipeline() = scope.launch {
        searchRequests
            .collectLatest { request ->
                val key = cacheKey(request.query, request.filter)
                val exact = searchCache.get(key)
                val cached = exact?.rows ?: prefixMatch(request.query, request.filter)
                _results.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
                if (exact != null && request.anonymous) {
                    searchSession = SearchSession(key, request.requestId, request.filter, exact.continuation)
                    return@collectLatest
                }

                val result = YtMusicRepository.searchPage(request.query, request.filter, request.anonymous)
                if (request.requestId != newestRequestId.get()) return@collectLatest
                _results.value = result.fold(
                    onSuccess = { page -> published(page, key, request.requestId) },
                    onFailure = { failure -> UiState.Error(friendly(failure)) },
                )
            }
    }

    fun loadMoreSearchResults() {
        val session = searchSession ?: return
        val token = session.continuation ?: return
        if (_searchLoadingMore.value) return
        _searchLoadingMore.value = true
        scope.launch {
            val next = YtMusicRepository.searchContinuation(token, session.filter)
            val stillCurrent = searchSession == session && session.requestId == newestRequestId.get()
            if (stillCurrent) {
                next.onSuccess { page ->
                    val current = (_results.value as? UiState.Success)?.data.orEmpty()
                    val merged = (current + page.rows).distinctBy(::searchResultKey)
                    searchCache.put(session.key, SearchCacheEntry(merged, page.continuation))
                    searchSession = session.copy(continuation = page.continuation)
                    _results.value = UiState.Success(merged)
                }
                _searchLoadingMore.value = false
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = scope.launch {
        fun stillWanted(input: String) = _query.value == input && !searchSubmitted

        suggestRequests
            .debounce(SUGGEST_DEBOUNCE_MS)
            .collectLatest { input ->
                if (!stillWanted(input)) return@collectLatest
                val fetched = YtMusicRepository.searchSuggestions(input).getOrNull()
                    ?: return@collectLatest
                if (!stillWanted(input)) return@collectLatest
                _suggestions.value = listOf(input) +
                    fetched.filterNot { it.equals(input, ignoreCase = true) }
            }
    }

    @OptIn(FlowPreview::class)
    private fun startTypeaheadMediaPipeline() = scope.launch {
        suggestRequests
            .debounce(TYPEAHEAD_MEDIA_DEBOUNCE_MS)
            .collectLatest { input ->
                if (input.isBlank()) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                if (searchSubmitted) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                val result = YtMusicRepository.searchTypeahead(input).getOrNull()
                if (_query.value != input) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                _typeaheadResults.value = result?.rows.orEmpty().take(TYPEAHEAD_MAX_RESULTS)
            }
    }

    private fun published(
        page: YtMusicRepository.SearchPage,
        key: String,
        requestId: Long,
    ): UiState<List<SearchResult>> {
        val rows = page.rows
        if (rows.isEmpty()) return UiState.Error(text(R.string.no_results))
        searchCache.put(key, SearchCacheEntry(rows, page.continuation))
        searchSession = SearchSession(key, requestId, _filter.value, page.continuation)
        prefetchTopResult(rows)
        return UiState.Success(rows)
    }

    private fun searchResultKey(row: SearchResult): String = when (row) {
        is SearchResult.TopTrack -> "v:${row.song.videoId}"
        is SearchResult.Track -> "v:${row.song.videoId}"
        is SearchResult.Browse -> "b:${row.item.browseId}"
    }

    private fun prefetchTopResult(rows: List<SearchResult>) {
        val song = rows.firstNotNullOfOrNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        } ?: return
        if (DirectSourceGate.isActive()) return
        scope.launch {
            runCatching {
                val audio = song
                if (SourceRegistry.parseTrackKey(audio.videoId) != null) return@runCatching
                val warmed = SourceResolver.prefetchSubstitute(
                    TrackMatcher.Target(
                        title = audio.title,
                        artist = audio.artist,
                        durationSec = TrackMatcher.secondsOf(audio.durationText),
                        album = audio.albumName,
                        isExplicit = audio.isExplicit,
                        isVideo = audio.isVideo,
                    ),
                )
                if (warmed != null) {
                    StreamChoice.remember(audio.videoId, warmed, substituted = true)
                    return@runCatching
                }
                StreamResolver.resolve(audio.videoId)
            }
        }
    }

    private companion object {
        private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
        private const val ADDON_SEARCH_LIMIT = 20
    }
}
