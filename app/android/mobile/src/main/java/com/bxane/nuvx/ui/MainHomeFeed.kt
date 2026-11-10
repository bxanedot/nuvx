package com.bxane.nuvx.ui

import android.app.Application
import android.util.Log
import com.bxane.nuvx.data.AppleArtistPhotos
import com.bxane.nuvx.data.TopPicks
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.TopPick
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.withoutRepeatsOf
import com.bxane.nuvx.data.stats.ListeningStats
import com.bxane.nuvx.data.stats.ReplayPeriod
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class MainHomeFeed(
    private val scope: CoroutineScope,
    private val application: Application,
    private val signedIn: StateFlow<Boolean>,
    private val listenerKey: () -> String?,
    private val friendly: (Throwable) -> String,
) {
    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    private var homeContinuation: String? = null

    private val homeSeenTitles = mutableSetOf<String>()

    private val _homeLoadingMore = MutableStateFlow(false)

    private val _homePendingShelves = MutableStateFlow(0)

    val homeLoadingMore: StateFlow<Boolean> =
        combine(_homeLoadingMore, _homePendingShelves) { paging, pending -> paging || pending > 0 }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _homeRecentlyPlayedLoading = MutableStateFlow(false)
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = _homeRecentlyPlayedLoading.asStateFlow()
    private val homeLoadGeneration = AtomicLong(0L)

    private val _topPicks = MutableStateFlow<List<TopPick>?>(null)
    val topPicks: StateFlow<List<TopPick>?> = _topPicks.asStateFlow()
    private val _topPicksLoading = MutableStateFlow(false)
    val topPicksLoading: StateFlow<Boolean> = _topPicksLoading.asStateFlow()
    private var topPicksJob: Job? = null

    fun refreshHomeRecents() {
        if (!signedIn.value) return
        val identity = listenerKey()
        val generation = homeLoadGeneration.get()
        scope.launch {
            YtMusicRepository.homeRecentlyPlayed().onSuccess { shelf ->
                if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
                shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
            }
        }
    }

    fun loadHome() {
        val identity = listenerKey()
        val generation = homeLoadGeneration.incrementAndGet()
        _home.value = UiState.Loading
        homeContinuation = null
        homeSeenTitles.clear()
        _homeLoadingMore.value = false
        _homeRecentlyPlayedLoading.value = signedIn.value
        _homePendingShelves.value = 1 + YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.size
        loadTopPicks(identity, rebuild = false)
        scope.launch {
            launch {
                try {
                    YtMusicRepository.home()
                        .onSuccess { feed ->
                            if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
                            homeContinuation = feed.continuation
                            publishHomeShelves(feed.shelves)
                        }
                        .onFailure { failure ->
                            if (isCurrentHomeLoad(identity, generation) && _home.value !is UiState.Success) {
                                _home.value = UiState.Error(friendly(failure))
                            }
                        }
                } finally {
                    homeShelfRequestSettled(identity, generation)
                }
            }
            if (signedIn.value) {
                launch {
                    YtMusicRepository.homeRecentlyPlayed()
                        .onSuccess { shelf ->
                            if (isCurrentHomeLoad(identity, generation)) {
                                _homeRecentlyPlayedLoading.value = false
                                shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
                            }
                        }
                        .onFailure {
                            if (isCurrentHomeLoad(identity, generation)) _homeRecentlyPlayedLoading.value = false
                        }
                }
            }
            YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
                launch {
                    try {
                        YtMusicRepository.homeSupplement(browseId).onSuccess { shelves ->
                            if (isCurrentHomeLoad(identity, generation)) publishHomeShelves(shelves)
                        }
                    } finally {
                        homeShelfRequestSettled(identity, generation)
                    }
                }
            }
        }
    }

    private fun loadTopPicks(identity: String?, rebuild: Boolean) {
        topPicksJob?.cancel()
        val file = File(application.filesDir, TOP_PICKS_FILE)
        val slot = topPicksSlot()
        topPicksJob = scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()?.let(TopPicks::decode)
            }?.takeIf { it.listener == identity && it.picks.isNotEmpty() }
            stored?.let { AppleArtistPhotos.seed(it.photos) }
            if (stored != null) {
                _topPicks.value = stored.picks
                topPicksSlotShown = stored.day
            } else if (!rebuild) {
                _topPicks.value = null
            }
            if (!rebuild && stored?.day == slot) {
                _topPicksLoading.value = false
                return@launch
            }
            _topPicksLoading.value = _topPicks.value == null
            val picks = buildTopPicks()?.takeIf { it.isNotEmpty() }
            if (identity != listenerKey()) return@launch
            _topPicksLoading.value = false
            if (picks == null) {
                if (_topPicks.value == null) _topPicks.value = emptyList()
                return@launch
            }
            _topPicks.value = picks
            topPicksSlotShown = slot
            withContext(Dispatchers.IO) {
                runCatching {
                    file.writeText(TopPicks.encode(TopPicks.Stored(identity, slot, picks, AppleArtistPhotos.snapshot())))
                }
            }
        }
    }

    private var topPicksSlotShown: String? = null

    fun refreshTopPicksIfStale() {
        if (topPicksJob?.isActive == true) return
        if (topPicksSlotShown == null || topPicksSlotShown == topPicksSlot()) return
        loadTopPicks(listenerKey(), rebuild = false)
    }

    private fun topPicksSlot(now: LocalDateTime = LocalDateTime.now()): String =
        "${now.toLocalDate()}T%02d".format(now.hour)

    private suspend fun buildTopPicks(): List<TopPick>? {
        val shelves = withTimeoutOrNull(8_000L) { _home.first { it !is UiState.Loading } }
            .let { (it as? UiState.Success)?.data.orEmpty() }
        return try {
            TopPicks.build(shelves, localTaste())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("nuvx", "topPicks failed: ${e.message}")
            null
        }
    }

    private suspend fun localTaste(): TopPicks.LocalTaste {
        val month = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
            ?: return TopPicks.LocalTaste()
        return TopPicks.LocalTaste(
            artists = month.artists.take(10).map { ArtistRef(name = it.title, browseId = it.browseId) },
            songs = month.songs.take(5).map { it.song },
            genres = month.genres.take(3).map { it.title },
        )
    }

    private fun homeShelfRequestSettled(identity: String?, generation: Long) {
        if (!isCurrentHomeLoad(identity, generation)) return
        _homePendingShelves.value = (_homePendingShelves.value - 1).coerceAtLeast(0)
    }

    private fun isCurrentHomeLoad(identity: String?, generation: Long) =
        identity == listenerKey() && generation == homeLoadGeneration.get()

    private fun publishHomeShelves(shelves: List<HomeShelf>, prepend: Boolean = false) {
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        if (prepend) {
            val replacing = shelves.map { it.title.lowercase(Locale.ROOT) }.toSet()
            homeSeenTitles.addAll(replacing)
            _home.value = UiState.Success(shelves + existing.filterNot {
                it.title.lowercase(Locale.ROOT) in replacing
            })
            return
        }
        val added = shelves.withoutRepeatsOf(existing)
            .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
        if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
    }

    suspend fun refreshHome(identity: String?) = coroutineScope {
        val recent = if (signedIn.value) async { YtMusicRepository.homeRecentlyPlayed() } else null
        if (topPicksJob?.isActive != true) loadTopPicks(identity, rebuild = false)
        YtMusicRepository.home().onSuccess { feed ->
            if (identity != listenerKey()) return@onSuccess
            homeContinuation = feed.continuation
            homeSeenTitles.clear()
            val shelves = feed.shelves.withoutRepeatsOf(emptyList())
                .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
            if (shelves.isNotEmpty()) _home.value = UiState.Success(shelves)
        }
        recent?.await()?.onSuccess { shelf ->
            if (identity == listenerKey()) shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
        }
    }

    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoadingMore.value) return
        val identity = listenerKey()
        _homeLoadingMore.value = true
        scope.launch {
            YtMusicRepository.moreHome(token).onSuccess { feed ->
                if (identity == listenerKey()) {
                    val existing = (_home.value as? UiState.Success)?.data ?: emptyList()
                    val added = feed.shelves.withoutRepeatsOf(existing)
                        .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
                    homeContinuation = feed.continuation.takeIf { added.isNotEmpty() }
                    if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
                }
            }
            if (identity == listenerKey()) _homeLoadingMore.value = false
        }
    }

    private companion object {
        private const val TOP_PICKS_FILE = "top_picks_v4.json"
    }
}
