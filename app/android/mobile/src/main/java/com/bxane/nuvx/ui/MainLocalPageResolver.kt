package com.bxane.nuvx.ui

import android.app.Application
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.ui.screens.CACHE_FOLDER_BROWSE_ID

internal class MainLocalPageResolver(
    private val application: Application,
    private val text: (Int) -> String,
    private val remotePageState: suspend (String) -> UiState<List<Song>>?,
) {
    suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = application
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        if (OfflinePlaylists.isOfflinePlaylist(browseId)) {
            val playlist = OfflinePlaylists.get(browseId) ?: return UiState.Error(text(R.string.no_tracks_here))
            return nonEmpty(LocalMediaRepository.present(context, playlist.songs)) {
                text(if (playlist.isLiked) R.string.offline_liked_empty else R.string.offline_playlist_empty)
            }
        }
        com.bxane.nuvx.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        remotePageState(browseId)?.let { return it }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            Downloads.recordIdOf(browseId) != null ->
                nonEmpty(downloadedPlaylist(browseId)) { text(R.string.downloaded_playlist_empty) }
            browseId == "local:downloads" ->
                nonEmpty(Downloads.getDownloadedSongs(context)) { "No downloaded tracks" }
            browseId == CACHE_FOLDER_BROWSE_ID -> cachedSongsState()
            browseId == "local:all" -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                nonEmpty(LocalMediaRepository.getLocalMusic(context)) { text(R.string.no_local_audio_found) }
            }
            else -> null
        }
    }

    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(application, id)
    }

    suspend fun downloadedCopyOf(browseId: String): List<Song> {
        val bare = browseId.removePrefix("VL")
        val id = Downloads.collections.value.keys.firstOrNull { it.removePrefix("VL") == bare } ?: return emptyList()
        return Downloads.getCollectionSongs(application, id)
    }

    private suspend fun cachedSongsState(): UiState<List<Song>> {
        val songs = AudioCache.cachedSongs().map { it.song }
        return if (songs.isEmpty()) UiState.Error(text(R.string.cached_songs_empty)) else UiState.Success(songs)
    }
}
