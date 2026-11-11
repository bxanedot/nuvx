package com.bxane.nuvx.ui

import android.app.Application
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SubscriptionState
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.data.spotify.SPOTIFY_PAGE_PREFIX
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

internal class MainDetailPageResolver(
    private val application: Application,
    private val scope: CoroutineScope,
    private val _detailStack: MutableStateFlow<List<DetailPage>>,
    private val browseTypeOf: (String, BrowseType) -> BrowseType,
    private val downloadedCopyOf: suspend (String) -> List<Song>,
    private val isRemoteLibrary: (String) -> Boolean,
    private val localPageState: suspend (String) -> UiState<List<Song>>?,
    private val withArtwork: (List<Song>, String?) -> List<Song>,
    private val setPlaylistOwned: (String, Boolean) -> Unit,
    private val fillIn: (String, String, String?) -> Unit,
    private val openSpotifyPage: (String, String, String, String?) -> Unit,
    private val text: (Int) -> String,
    private val friendly: (Throwable) -> String,
) {
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
        scope.launch {
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
            val remote = isRemoteLibrary(browseId)
            val onDevice = if (localPlaylist == null && !remote && !browseId.startsWith("local:")) {
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
                credit = application.getString(R.string.local_playlist_subtitle, localPlaylist.songs.size)
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
                                UiState.Success(withArtwork(page.songs, thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(friendly(it)) },
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
                                suggested = withArtwork(page.suggested, thumbnailUrl ?: artwork)
                                library = page.library
                                UiState.Success(withArtwork(page.songs, thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(friendly(it)) },
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

}
