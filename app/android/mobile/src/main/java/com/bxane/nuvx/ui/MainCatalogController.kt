package com.bxane.nuvx.ui

import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.catalogReleases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class MainCatalogController(
    private val scope: CoroutineScope,
    private val withArtwork: (List<Song>, String?) -> List<Song>,
) {
    private val _catalogLoading = MutableStateFlow<Set<String>>(emptySet())

    val catalogLoading: StateFlow<Set<String>> = _catalogLoading.asStateFlow()

    fun collectCatalog(page: DetailPage, onResult: (List<Pair<ShelfItem, List<Song>>>) -> Unit) {
        if (page.browseId in _catalogLoading.value) return
        _catalogLoading.update { it + page.browseId }
        scope.launch {
            val gate = Semaphore(4)
            val releases = try {
                page.sections.catalogReleases().map { release ->
                    async {
                        gate.withPermit {
                            YtMusicRepository.allSongs(release.browseId!!).getOrNull()
                                ?.map { it.copy(albumName = it.albumName ?: release.title) }
                                ?.let { withArtwork(it, release.thumbnailUrl) }
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
}
