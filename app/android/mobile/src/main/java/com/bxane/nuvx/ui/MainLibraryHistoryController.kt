package com.bxane.nuvx.ui

import android.util.Log
import com.bxane.nuvx.R
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class MainLibraryHistoryController(
    private val scope: CoroutineScope,
    private val isSignedIn: () -> Boolean,
    private val listenerKey: () -> String?,
    private val text: (Int) -> String,
    private val friendly: (Throwable) -> String,
    private val _library: MutableStateFlow<UiState<LibraryPage>>,
    private val _history: MutableStateFlow<UiState<List<Song>>>,
) {
    private var likedSyncJob: Job? = null

    fun loadLibrary() {
        if (!isSignedIn()) return
        val identity = listenerKey()
        _library.value = UiState.Loading
        scope.launch { fetchLibrary(identity) }
    }

    suspend fun fetchLibrary(identity: String?) {
        val next = YtMusicRepository.library().fold(
            onSuccess = { page ->
                page.likedContinuation?.let { token -> syncLikedMusic(identity, token) }
                if (page.isEmpty) UiState.Error(text(R.string.library_empty))
                else UiState.Success(page.copy(likedContinuation = null))
            },
            onFailure = { UiState.Error(friendly(it)) },
        )
        if (identity == listenerKey()) _library.value = next
    }

    private fun syncLikedMusic(identity: String?, token: String) {
        likedSyncJob?.cancel()
        likedSyncJob = scope.launch {
            YtMusicRepository.syncLikedMusic(token) { next ->
                if (identity != listenerKey()) null else YtMusicRepository.moreSongs(next).getOrNull()
            }
        }
    }

    fun loadHistory() {
        if (!isSignedIn()) {
            _history.value = UiState.Error(text(R.string.history_sign_in_required))
            return
        }
        val identity = listenerKey()
        _history.value = UiState.Loading
        scope.launch {
            val next = YtMusicRepository.history().fold(
                onSuccess = { songs ->
                    if (songs.isEmpty()) UiState.Error(text(R.string.history_empty))
                    else UiState.Success(songs)
                },
                onFailure = { UiState.Error(friendly(it)) },
            )
            if (identity == listenerKey()) _history.value = next
        }
    }

    fun removeFromHistory(token: String, onSuccess: () -> Unit = {}, onFailure: (Throwable) -> Unit = {}) {
        scope.launch {
            YtMusicRepository.removeFromHistory(token).onSuccess {
                val current = _history.value
                if (current is UiState.Success) {
                    _history.value = current.copy(data = current.data.filterNot { it.feedbackToken == token })
                }
                YtMusicRepository.invalidateHistoryCache()
                onSuccess()
            }.onFailure {
                Log.w("nuvx", "removeFromHistory failed: ${it.message}")
                onFailure(it)
            }
        }
    }

    fun cancelLikedSync() = likedSyncJob?.cancel()
}
