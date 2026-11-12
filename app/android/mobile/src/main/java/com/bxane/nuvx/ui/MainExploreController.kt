package com.bxane.nuvx.ui

import com.bxane.nuvx.R
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class MainExploreController(
    private val scope: CoroutineScope,
    private val text: (Int) -> String,
    private val friendly: (Throwable) -> String,
) {
    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()

    private val _exploreShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val exploreShelves: StateFlow<List<HomeShelf>> = _exploreShelves.asStateFlow()

    private val _selectedMoodGenre = MutableStateFlow<MoodGenre?>(null)
    val selectedMoodGenre: StateFlow<MoodGenre?> = _selectedMoodGenre.asStateFlow()

    private val _moodGenreShelves = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val moodGenreShelves: StateFlow<UiState<List<HomeShelf>>> = _moodGenreShelves.asStateFlow()
    private var moodGenreRequestId = 0

    private val _podcasts = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val podcasts: StateFlow<UiState<List<HomeShelf>>> = _podcasts.asStateFlow()

    fun loadExplore() {
        exploreRequested = true
        _explore.value = UiState.Loading
        scope.launch { fetchExplore() }
    }

    private var exploreRequested = false

    fun onExploreShown() {
        if (!exploreRequested) loadExplore()
    }

    suspend fun fetchExplore() {
        scope.launch {
            YtMusicRepository.homeSupplement(EXPLORE_BROWSE_ID).onSuccess { _exploreShelves.value = it }
        }
        val state = YtMusicRepository.moodAndGenres().fold(
            onSuccess = { sections ->
                if (sections.isEmpty()) {
                    UiState.Error(text(R.string.nothing_to_explore))
                } else {
                    UiState.Success(sections)
                }
            },
            onFailure = { UiState.Error(friendly(it)) },
        )
        _explore.value = state
        (state as? UiState.Success)?.data?.let(::loadMoodGenreArtwork)
    }

    private fun loadMoodGenreArtwork(sections: List<MoodGenreSection>) {
        scope.launch {
            val limiter = Semaphore(4)
            coroutineScope {
                sections.flatMap(MoodGenreSection::items).forEach { item ->
                    launch {
                        val artwork = limiter.withPermit {
                            YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull()
                        } ?: return@launch
                        val current = (_explore.value as? UiState.Success)?.data ?: return@launch
                        _explore.value = UiState.Success(current.map { section ->
                            section.copy(items = section.items.map { currentItem ->
                                if (currentItem.browseId == item.browseId && currentItem.params == item.params) {
                                    currentItem.copy(thumbnailUrl = artwork)
                                } else {
                                    currentItem
                                }
                            })
                        })
                    }
                }
            }
        }
    }

    fun openMoodGenre(item: MoodGenre) {
        val requestId = ++moodGenreRequestId
        _selectedMoodGenre.value = item
        _moodGenreShelves.value = UiState.Loading
        scope.launch {
            val result = YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
                onSuccess = { shelves ->
                    if (shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                    else UiState.Success(shelves)
                },
                onFailure = { UiState.Error(friendly(it)) },
            )
            if (requestId == moodGenreRequestId) _moodGenreShelves.value = result
        }
    }

    fun loadPodcasts(force: Boolean = false) {
        if (!force && _podcasts.value is UiState.Success) return
        _podcasts.value = UiState.Loading
        scope.launch {
            _podcasts.value = YtMusicRepository.podcasts().fold(
                onSuccess = { shelves ->
                    if (shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                    else UiState.Success(shelves)
                },
                onFailure = { UiState.Error(friendly(it)) },
            )
        }
    }

    fun closeMoodGenre(): Boolean {
        if (_selectedMoodGenre.value == null) return false
        ++moodGenreRequestId
        _selectedMoodGenre.value = null
        return true
    }
}

private const val EXPLORE_BROWSE_ID = "FEmusic_explore"
