package com.bxane.nuvx.ui

import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.SongMenu
import com.bxane.nuvx.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class MainCollectionController(
    private val scope: CoroutineScope,
    private val requireSignIn: () -> Boolean,
    private val markLibraryStale: () -> Unit,
    private val _library: MutableStateFlow<UiState<LibraryPage>>,
    private val _detailStack: MutableStateFlow<List<DetailPage>>,
    private val _songMenu: MutableStateFlow<SongMenu?>,
) {
    private val _releaseLibrary = MutableStateFlow<Map<String, LibraryState>>(emptyMap())

    val releaseLibrary: StateFlow<Map<String, LibraryState>> = _releaseLibrary.asStateFlow()

    fun loadReleaseLibrary(browseId: String) {
        if (browseId in _releaseLibrary.value) return
        scope.launch {
            YtMusicRepository.releaseLibraryState(browseId).getOrNull()?.let { state ->
                _releaseLibrary.value += (browseId to state)
            }
        }
    }

    fun toggleReleaseLibrary(browseId: String) {
        if (!requireSignIn()) return
        scope.launch {
            val current = _releaseLibrary.value[browseId]
                ?: YtMusicRepository.releaseLibraryState(browseId).getOrNull()
                ?: return@launch
            val target = !current.saved
            _releaseLibrary.value += (browseId to current.copy(saved = target))
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                markLibraryStale()
            } else {
                _releaseLibrary.value += (browseId to current)
            }
        }
    }

    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, LikeState.overrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    fun setLike(videoId: String, status: LikeStatus) {
        if (!requireSignIn()) return
        val previous = likeStatusOf(videoId)
        if (previous == status) return
        LikeState.set(videoId, status)
        scope.launch {
            YtMusicRepository.rate(videoId, status).fold(
                onSuccess = {
                    markLibraryStale()
                    if (status != LikeStatus.LIKE) dropFromLikedLists(videoId)
                    val unliked = previous == LikeStatus.LIKE &&
                        status == LikeStatus.INDIFFERENT
                    if (unliked) forgetFromLibrary(videoId)
                },
                onFailure = {
                    LikeState.set(videoId, previous)
                },
            )
        }
    }

    private suspend fun forgetFromLibrary(videoId: String): Boolean {
        val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return false
        val token = menu.removeFromLibraryToken?.takeIf { menu.inLibrary } ?: return false
        if (YtMusicRepository.setLibraryStatus(token).isFailure) return false
        _songMenu.value = _songMenu.value?.copy(inLibrary = false)
        return true
    }

    private fun dropFromLikedLists(videoId: String) {
        val library = (_library.value as? UiState.Success)?.data
        if (library != null && library.likedSongs.any { it.videoId == videoId }) {
            _library.value = UiState.Success(
                library.copy(likedSongs = library.likedSongs.filterNot { it.videoId == videoId }),
            )
        }
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success)?.data
            if (page.browseId != YtMusicRepository.LIKED_MUSIC || songs == null) {
                page
            } else {
                page.copy(songs = UiState.Success(songs.filterNot { it.videoId == videoId }))
            }
        }
    }

    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    fun toggleDislike(videoId: String): LikeStatus? {
        if (!requireSignIn()) return null
        val previous = likeStatusOf(videoId)
        setLike(
            videoId,
            if (previous == LikeStatus.DISLIKE) {
                LikeStatus.INDIFFERENT
            } else {
                LikeStatus.DISLIKE
            },
        )
        return previous
    }

    fun toggleLibrary(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value.firstOrNull { it.browseId == browseId }?.library ?: return
        val target = !current.saved
        setSavedOnPage(browseId, target)
        scope.launch {
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                markLibraryStale()
            } else {
                setSavedOnPage(browseId, current.saved)
            }
        }
    }

    fun toggleSubscription(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value
            .firstOrNull { it.browseId == browseId }?.subscription ?: return
        val target = !current.subscribed
        setSubscribedOnPage(browseId, target)
        scope.launch {
            if (YtMusicRepository.setSubscribed(current.channelId, target).isSuccess) {
                markLibraryStale()
            } else {
                setSubscribedOnPage(browseId, current.subscribed)
            }
        }
    }

    private fun setSubscribedOnPage(browseId: String, subscribed: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val subscription = page.subscription
            if (page.browseId != browseId || subscription == null) {
                page
            } else {
                page.copy(subscription = subscription.copy(subscribed = subscribed))
            }
        }
    }

    private fun setSavedOnPage(browseId: String, saved: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val library = page.library
            if (page.browseId != browseId || library == null) {
                page
            } else {
                page.copy(library = library.copy(saved = saved))
            }
        }
    }
}
