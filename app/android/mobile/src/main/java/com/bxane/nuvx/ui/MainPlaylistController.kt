package com.bxane.nuvx.ui

import com.bxane.nuvx.R
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.PlaylistPrivacy
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.ui.navigation.AppNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class MainPlaylistController(
    private val scope: CoroutineScope,
    private val authStore: AuthStore,
    private val listenerKey: () -> String?,
    private val requireSignIn: () -> Boolean,
    private val markLibraryStale: () -> Unit,
    private val onPlaylistOwned: (String, Boolean) -> Unit,
    private val text: (Int) -> String,
    private val withArtwork: (List<Song>, String?) -> List<Song>,
    private val navigator: AppNavigator,
    private val _playlists: MutableStateFlow<List<UserPlaylist>>,
    private val _playlistsLoading: MutableStateFlow<Boolean>,
    private val _playlistOwned: MutableStateFlow<Map<String, Boolean>>,
    private val _library: MutableStateFlow<UiState<LibraryPage>>,
    private val _detailStack: MutableStateFlow<List<DetailPage>>,
) {
    fun loadPlaylists() {
        if (!requireSignIn() || _playlistsLoading.value) return
        val identity = listenerKey()
        _playlistsLoading.value = true
        scope.launch {
            YtMusicRepository.userPlaylists().onSuccess { if (identity == listenerKey()) _playlists.value = it }
            if (identity == listenerKey()) _playlistsLoading.value = false
        }
    }

    private fun editPlaylistShelf(edit: (List<ShelfItem>) -> List<ShelfItem>) {
        val page = (_library.value as? UiState.Success)?.data ?: return
        val existing = page.shelves.firstOrNull { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        val items = edit(existing?.items.orEmpty())
        if (items == existing?.items) return
        val shelves = when {
            existing == null && items.isEmpty() -> return
            existing == null ->
                listOf(HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, items)) + page.shelves
            items.isEmpty() -> page.shelves.filterNot { it === existing }
            else -> page.shelves.map { if (it === existing) existing.copy(items = items) else it }
        }
        _library.value = UiState.Success(page.copy(shelves = shelves))
    }

    private fun setPlaylistTitle(playlist: UserPlaylist, title: String) {
        _playlists.value = _playlists.value.map {
            if (it.playlistId == playlist.playlistId) it.copy(title = title) else it
        }
        editPlaylistShelf { items ->
            items.map { if (it.browseId == playlist.browseId) it.copy(title = title) else it }
        }
        _detailStack.value = _detailStack.value.map {
            if (it.browseId == playlist.browseId) it.copy(title = title) else it
        }
    }

    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) {
        if (!requireSignIn() || playlists.isEmpty()) return
        scope.launch {
            val outcomes = playlists.map { addOne(it, song) }
            onResult(
                outcomes.count { it == AddOutcome.ADDED },
                outcomes.count { it == AddOutcome.ALREADY_THERE },
                outcomes.count { it == AddOutcome.FAILED },
            )
        }
    }

    private enum class AddOutcome { ADDED, ALREADY_THERE, FAILED }

    private suspend fun addOne(playlist: UserPlaylist, song: Song): AddOutcome {
        val openSongs = (_detailStack.value.firstOrNull { it.browseId == playlist.browseId }
            ?.songs as? UiState.Success)?.data
        val known = openSongs
            ?: YtMusicRepository.allSongs(playlist.browseId).getOrNull()
        if (known?.any { it.videoId == song.videoId } == true) return AddOutcome.ALREADY_THERE
        return YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)).fold(
            onSuccess = { added ->
                markLibraryStale()
                appendToOpenPlaylist(playlist.browseId, song, added[song.videoId])
                AddOutcome.ADDED
            },
            onFailure = { AddOutcome.FAILED },
        )
    }

    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) {
        if (!requireSignIn()) return
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        scope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    onPlaylistOwned("VL$playlistId", true)
                    markLibraryStale()
                    val created = UserPlaylist(
                        playlistId = playlistId,
                        title = name,
                        subtitle = if (song != null) "1 song" else "",
                        thumbnailUrl = song?.thumbnailUrl,
                    )
                    _playlists.value = listOf(created) +
                        _playlists.value.filterNot { it.playlistId == created.playlistId }
                    editPlaylistShelf { items ->
                        listOf(
                            ShelfItem(
                                title = created.title,
                                subtitle = created.subtitle,
                                thumbnailUrl = created.thumbnailUrl,
                                videoId = null,
                                browseId = created.browseId,
                            ),
                        ) + items.filterNot { it.browseId == created.browseId }
                    }
                },
                onFailure = {},
            )
        }
    }

    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        scope.launch {
            if (authStore.isSignedIn) {
                val initialBatch = videoIds.take(50)
                YtMusicRepository.createPlaylist(
                    title = name,
                    privacy = privacy,
                    videoIds = initialBatch,
                ).fold(
                    onSuccess = { playlistId ->
                        if (videoIds.size > 50) {
                            videoIds.drop(50).chunked(50).forEach { chunk ->
                                YtMusicRepository.addToPlaylist(playlistId, chunk)
                            }
                        }
                        onPlaylistOwned("VL$playlistId", true)
                        markLibraryStale()
                        val created = UserPlaylist(
                            playlistId = playlistId,
                            title = name,
                            subtitle = "${videoIds.size} songs",
                            thumbnailUrl = null,
                        )
                        _playlists.value = listOf(created) +
                            _playlists.value.filterNot { it.playlistId == created.playlistId }
                        editPlaylistShelf { items ->
                            listOf(
                                ShelfItem(
                                    title = created.title,
                                    subtitle = created.subtitle,
                                    thumbnailUrl = created.thumbnailUrl,
                                    videoId = null,
                                    browseId = created.browseId,
                                ),
                            ) + items.filterNot { it.browseId == created.browseId }
                        }
                        onResult?.invoke(created.browseId, created.title, false)
                    },
                    onFailure = {
                        val local = com.bxane.nuvx.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                        onResult?.invoke(local.browseId, local.title, true)
                    },
                )
            } else {
                val local = com.bxane.nuvx.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                onResult?.invoke(local.browseId, local.title, true)
            }
        }
    }

    fun removeFromPlaylist(browseId: String, song: Song) {
        val setVideoId = song.setVideoId ?: return
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        scope.launch {
            YtMusicRepository.removeFromPlaylist(
                playlistId,
                listOf(setVideoId to song.videoId),
            ).fold(
                onSuccess = {
                    markLibraryStale()
                    _detailStack.value = _detailStack.value.map { page ->
                        val songs = (page.songs as? UiState.Success)?.data
                        if (page.browseId != browseId || songs == null) {
                            page
                        } else {
                            page.copy(
                                songs = UiState.Success(
                                    songs.filterNot { it.setVideoId == setVideoId },
                                ),
                            )
                        }
                    }
                },
                onFailure = {},
            )
        }
    }

    fun addSuggestedSong(browseId: String, song: Song) {
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        scope.launch {
            YtMusicRepository.addToPlaylist(playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    markLibraryStale()
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != browseId) {
                            page
                        } else {
                            page.copy(
                                suggestedSongs = page.suggestedSongs
                                    .filterNot { it.videoId == song.videoId },
                            )
                        }
                    }
                    appendToOpenPlaylist(browseId, song, added[song.videoId])
                },
                onFailure = {},
            )
        }
    }

    private fun appendToOpenPlaylist(browseId: String, song: Song, setVideoId: String?) {
        val added = song.copy(setVideoId = setVideoId)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId != browseId) return@map page
            val songs = when (val state = page.songs) {
                is UiState.Success -> state.data
                is UiState.Error -> if (state.message == text(R.string.no_tracks_here)) emptyList() else return@map page
                UiState.Loading -> return@map page
            }
            if (songs.any { it.videoId == song.videoId }) return@map page
            page.copy(
                songs = UiState.Success(
                    songs + added.copy(
                        thumbnailUrl = added.thumbnailUrl ?: page.thumbnailUrl,
                    ),
                ),
            )
        }
    }

    fun renamePlaylist(playlist: UserPlaylist, title: String) {
        if (!requireSignIn()) return
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        scope.launch {
            YtMusicRepository.renamePlaylist(playlist.playlistId, name).fold(
                onSuccess = {
                    setPlaylistTitle(playlist, name)
                    markLibraryStale()
                },
                onFailure = {},
            )
        }
    }

    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) {
        if (!requireSignIn()) return onResult(Result.failure(IllegalStateException("signed out")))
        scope.launch {
            onResult(YtMusicRepository.playlistEntries(playlist.browseId))
        }
    }

    fun reorderPlaylist(
        playlist: UserPlaylist,
        original: List<Song>,
        reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!requireSignIn()) return onResult(false)
        scope.launch {
            YtMusicRepository.reorderPlaylist(
                playlist.playlistId,
                current = original.mapNotNull { it.setVideoId },
                target = reordered.mapNotNull { it.setVideoId },
            ).fold(
                onSuccess = {
                    markLibraryStale()
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != playlist.browseId || page.songs !is UiState.Success) {
                            page
                        } else {
                            page.copy(songs = UiState.Success(withArtwork(reordered, page.thumbnailUrl)))
                        }
                    }
                    onResult(true)
                },
                onFailure = { onResult(false) },
            )
        }
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        if (!requireSignIn()) return
        scope.launch {
            YtMusicRepository.deletePlaylist(playlist.playlistId).fold(
                onSuccess = {
                    _playlists.value = _playlists.value
                        .filterNot { it.playlistId == playlist.playlistId }
                    editPlaylistShelf { items ->
                        items.filterNot { it.browseId == playlist.browseId }
                    }
                    _detailStack.value = _detailStack.value
                        .filterNot { it.browseId == playlist.browseId }
                    navigator.onDetailStackShrunk(_detailStack.value.size)
                    markLibraryStale()
                },
                onFailure = {},
            )
        }
    }

    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null || _playlistOwned.value[browseId] != true) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    fun resolvePlaylistOwnership(browseId: String?) {
        if (!requireSignIn() || browseId == null) return
        if (browseId in _playlistOwned.value || browseId in ownershipInFlight) return
        if (_playlists.value.none { it.browseId == browseId }) return
        ownershipInFlight += browseId
        scope.launch {
            YtMusicRepository.playlistOwned(browseId).onSuccess { owned ->
                if (owned != null) onPlaylistOwned(browseId, owned)
            }
            ownershipInFlight -= browseId
        }
    }

    private val ownershipInFlight = mutableSetOf<String>()

    fun clearOwnershipInFlight() = ownershipInFlight.clear()
}
