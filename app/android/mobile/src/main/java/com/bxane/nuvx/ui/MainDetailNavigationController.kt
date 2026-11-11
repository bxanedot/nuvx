package com.bxane.nuvx.ui

import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalGroup
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.ui.navigation.AppNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MainDetailNavigationController(
    private val scope: CoroutineScope,
    private val localPageState: suspend (String) -> UiState<List<Song>>?,
    private val text: (Int) -> String,
) {
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()
    internal val mutableDetailStack = _detailStack

    fun clear(navigator: AppNavigator) {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

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

    fun retitle(browseId: String, title: String) {
        if (title.isBlank()) return
        _detailStack.value = _detailStack.value.map { if (it.browseId == browseId) it.copy(title = title) else it }
    }

    fun reloadLocal(browseId: String) {
        scope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    fun reloadRemote(browseId: String) {
        if (_detailStack.value.any { page -> page.browseId == browseId }) {
            reloadLocal(browseId)
        }
    }

    fun close(navigator: AppNavigator): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }
}
