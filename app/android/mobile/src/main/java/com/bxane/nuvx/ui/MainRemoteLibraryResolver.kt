package com.bxane.nuvx.ui

import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UiState

internal class MainRemoteLibraryResolver(
    private val text: (Int) -> String,
) {
    private data class RemoteLibrary(
        val emptyRes: Int,
        val songs: suspend () -> List<Song>,
    )

    fun isRemoteLibrary(browseId: String): Boolean = remoteLibrary(browseId) != null

    suspend fun pageState(browseId: String): UiState<List<Song>>? =
        remoteLibrary(browseId)?.let { remoteSongsState(it) }

    private fun remoteLibrary(browseId: String): RemoteLibrary? = when (browseId) {
        com.bxane.nuvx.data.webdav.WebDavConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.webdav_empty,
                com.bxane.nuvx.data.webdav.WebDavRepository::getSongs,
            )
        com.bxane.nuvx.data.smb.SmbConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.smb_empty,
                com.bxane.nuvx.data.smb.SmbRepository::getSongs,
            )
        else -> null
    }

    private suspend fun remoteSongsState(remote: RemoteLibrary): UiState<List<Song>> =
        com.bxane.nuvx.data.remote.RemoteListing.state(runCatching { remote.songs() }, text(remote.emptyRes))
}
