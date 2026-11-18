package com.licht.nuvx.ui

import com.licht.nuvx.R
import com.licht.nuvx.auth.AuthStore
import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.model.DetailPage
import com.licht.nuvx.data.model.HomeShelf
import com.licht.nuvx.data.model.LibraryPage
import com.licht.nuvx.data.model.PlaylistPrivacy
import com.licht.nuvx.data.model.ShelfItem
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.model.UiState
import com.licht.nuvx.data.model.UserPlaylist
import com.licht.nuvx.ui.navigation.AppNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MainPlaylistController(
    private val scope: CoroutineScope,
    private val authStore: AuthStore,
    private val listenerKey: () -> String?,
    private val requireSignIn: () -> Boolean,
    private val markLibraryStale: () -> Unit,
    private val text: (Int) -> String,
    private val withArtwork: (List<Song>, String?) -> List<Song>,
    private val playlistArtwork: (String) -> String?,
    private val navigator: AppNavigator,
    private val _library: MutableStateFlow<UiState<LibraryPage>>,
    private val _detailStack: MutableStateFlow<List<DetailPage>>,
) {
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()
    internal val mutablePlaylists = _playlists

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()
    internal val mutablePlaylistsLoading = _playlistsLoading

    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()
    internal val mutablePlaylistOwned = _playlistOwned

    fun loadPlaylists() {
        if (!requireSignIn() || _playlistsLoading.value) return
        val identity = listenerKey()
        _playlistsLoading.value = true
        scope.launch {
            YtMusicRepository.userPlaylists().onSuccess {
                if (identity == listenerKey()) _playlists.value = it.map { playlist ->
                    playlist.copy(thumbnailUrl = playlistArtwork(playlist.browseId) ?: playlist.thumbnailUrl)
                }
            }
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
        if (playlists.isEmpty() || (playlists.any { !it.browseId.startsWith("local:playlist:") } && !requireSignIn())) return
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
        if (playlist.browseId.startsWith("local:playlist:")) {
            val current = com.licht.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(playlist.playlistId)
                ?: return AddOutcome.FAILED
            if (current.songs.any { it.videoId == song.videoId }) return AddOutcome.ALREADY_THERE
            if (!com.licht.nuvx.data.spotify.LocalPlaylistStore.addSong(playlist.playlistId, song)) return AddOutcome.FAILED
            appendToOpenPlaylist(playlist.browseId, song, null)
            return AddOutcome.ADDED
        }
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
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        // Signed out there is no YouTube account to save to, so keep it on this device instead of doing nothing.
        if (com.licht.nuvx.data.settings.AppSettings.createPlaylistsLocally.value || !requireSignIn()) {
            val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.createNuvxPlaylist(name, listOfNotNull(song))
            _playlists.value = listOf(UserPlaylist(local.browseId, local.title, "${local.songs.size} songs", local.songs.firstOrNull()?.thumbnailUrl)) +
                _playlists.value.filterNot { it.browseId == local.browseId }
            setPlaylistOwned(local.browseId, true)
            return
        }
        if (!requireSignIn()) return
        scope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    setPlaylistOwned("VL$playlistId", true)
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

    /** A playlist read from a file always stays on this device: the user's file is never uploaded. */
    fun importPlaylist(title: String, songs: List<Song>): String {
        val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.createNuvxPlaylist(title.trim().ifBlank { text(R.string.new_playlist) }, songs)
        _playlists.value = listOf(UserPlaylist(local.browseId, local.title, "${local.songs.size} songs", local.songs.firstOrNull()?.thumbnailUrl)) +
            _playlists.value.filterNot { it.browseId == local.browseId }
        setPlaylistOwned(local.browseId, true)
        return local.browseId
    }

    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        onResult: ((browseId: String?, title: String, savedLocally: Boolean, failedSongs: Int) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        scope.launch {
            if (com.licht.nuvx.data.settings.AppSettings.createPlaylistsLocally.value) {
                val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.createNuvxPlaylist(name, songs)
                val missingSongs = (videoIds.size - songs.size).coerceAtLeast(0)
                onResult?.invoke(local.browseId, local.title, true, missingSongs)
                return@launch
            }
            if (authStore.isSignedIn) {
                val initialBatch = videoIds.take(50)
                YtMusicRepository.createPlaylist(
                    title = name,
                    privacy = privacy,
                    videoIds = initialBatch,
                ).fold(
                    onSuccess = { playlistId ->
                        var failedSongs = 0
                        if (videoIds.size > 50) {
                            videoIds.drop(50).chunked(50).forEach { chunk ->
                                YtMusicRepository.addToPlaylist(playlistId, chunk).onFailure {
                                    failedSongs += chunk.size
                                }
                            }
                        }
                        setPlaylistOwned("VL$playlistId", true)
                        markLibraryStale()
                        val created = UserPlaylist(
                            playlistId = playlistId,
                            title = name,
                            subtitle = "${videoIds.size - failedSongs} songs",
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
                        onResult?.invoke(created.browseId, created.title, false, failedSongs)
                    },
                    onFailure = {
                        val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                        onResult?.invoke(local.browseId, local.title, true, 0)
                    },
                )
            } else {
                val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
                onResult?.invoke(local.browseId, local.title, true, 0)
            }
        }
    }

    /** For a nuvx playlist, returns an undo that puts the song back where it was. */
    fun removeFromPlaylist(browseId: String, song: Song): (() -> Unit)? {
        val store = com.licht.nuvx.data.spotify.LocalPlaylistStore
        val local = store.getPlaylist(browseId)
        if (local != null) {
            val at = local.songs.indexOfFirst { it.videoId == song.videoId }
            val removed = local.songs.getOrNull(at)
            store.removeSong(local.id, song.videoId)
            showLocalSongs(browseId, local.songs.filterNot { it.videoId == song.videoId })
            return removed?.let {
                {
                    if (store.putBack(local.id, it, at)) store.getPlaylist(local.id)?.let { back -> showLocalSongs(browseId, back.songs) }
                }
            }
        }
        val setVideoId = song.setVideoId ?: return null
        if (!requireSignIn()) return null
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
        return null
    }

    private fun showLocalSongs(browseId: String, songs: List<Song>) {
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId == browseId) page.copy(songs = UiState.Success(songs)) else page
        }
    }

    fun addSuggestedSong(browseId: String, song: Song) {
        val local = com.licht.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(browseId)
        if (local != null) {
            if (com.licht.nuvx.data.spotify.LocalPlaylistStore.addSong(local.id, song)) {
                _detailStack.value = _detailStack.value.map { page ->
                    if (page.browseId != browseId) page else page.copy(suggestedSongs = page.suggestedSongs.filterNot { it.videoId == song.videoId })
                }
                appendToOpenPlaylist(browseId, song, null)
            }
            return
        }
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
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        if (playlist.browseId.startsWith("local:playlist:")) {
            com.licht.nuvx.data.spotify.LocalPlaylistStore.renamePlaylist(playlist.playlistId, name)
            setPlaylistTitle(playlist, name)
            return
        }
        if (!requireSignIn()) return
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

    fun setArtwork(browseId: String, artwork: String) {
        _playlists.value = _playlists.value.map {
            if (it.browseId == browseId || it.playlistId == browseId) it.copy(thumbnailUrl = artwork) else it
        }
        val page = (_library.value as? UiState.Success)?.data ?: return
        val shelves = page.shelves.map { shelf ->
            shelf.copy(items = shelf.items.map { item ->
                if (item.browseId == browseId || item.browseId == browseId.removePrefix("VL")) item.copy(thumbnailUrl = artwork) else item
            })
        }
        _library.value = UiState.Success(page.copy(shelves = shelves))
    }

    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) {
        if (playlist.browseId.startsWith("local:playlist:")) {
            val songs = com.licht.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(playlist.playlistId)?.songs
            return onResult(songs?.let(Result.Companion::success) ?: Result.failure(IllegalStateException("playlist missing")))
        }
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
        if (playlist.browseId.startsWith("local:playlist:")) {
            val saved = com.licht.nuvx.data.spotify.LocalPlaylistStore.replaceSongs(playlist.playlistId, reordered)
            if (saved) _detailStack.value = _detailStack.value.map { page ->
                if (page.browseId == playlist.browseId) page.copy(songs = UiState.Success(withArtwork(reordered, page.thumbnailUrl))) else page
            }
            return onResult(saved)
        }
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

    /** Deletes [playlist]. For a nuvx playlist, returns an undo that puts it back with its songs and position. */
    fun deletePlaylist(playlist: UserPlaylist): (() -> Unit)? {
        if (playlist.browseId.startsWith("local:playlist:")) {
            val store = com.licht.nuvx.data.spotify.LocalPlaylistStore
            val stored = store.getPlaylist(playlist.playlistId)
            val storeIndex = store.playlists.value.indexOf(stored)
            val listIndex = _playlists.value.indexOfFirst { it.playlistId == playlist.playlistId }
            store.deletePlaylist(playlist.playlistId)
            _playlists.value = _playlists.value.filterNot { it.playlistId == playlist.playlistId }
            _detailStack.value = _detailStack.value.filterNot { it.browseId == playlist.browseId }
            navigator.onDetailStackShrunk(_detailStack.value.size)
            return stored?.let {
                {
                    store.restore(it, storeIndex)
                    if (_playlists.value.none { p -> p.playlistId == playlist.playlistId }) {
                        _playlists.value = _playlists.value.toMutableList().apply { add(listIndex.coerceIn(0, size), playlist) }
                    }
                }
            }
        }
        if (!requireSignIn()) return null
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
        return null
    }

    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null) return null
        _playlists.value.firstOrNull { it.browseId == browseId && it.browseId.startsWith("local:playlist:") }?.let { return it }
        com.licht.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { local ->
            return UserPlaylist(local.browseId, local.title, "${local.songs.size} songs", local.songs.firstOrNull()?.thumbnailUrl)
        }
        if (_playlistOwned.value[browseId] != true) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }

    fun resolvePlaylistOwnership(browseId: String?) {
        if (browseId?.startsWith("local:playlist:") == true) return
        if (!requireSignIn() || browseId == null) return
        if (browseId in _playlistOwned.value || browseId in ownershipInFlight) return
        if (_playlists.value.none { it.browseId == browseId }) return
        ownershipInFlight += browseId
        scope.launch {
            YtMusicRepository.playlistOwned(browseId).onSuccess { owned ->
                if (owned != null) setPlaylistOwned(browseId, owned)
            }
            ownershipInFlight -= browseId
        }
    }

    private val ownershipInFlight = mutableSetOf<String>()

    fun clearOwnershipInFlight() = ownershipInFlight.clear()
}
