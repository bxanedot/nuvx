// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.data

import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.model.PlaylistPrivacy
import com.bxane.nuvx.data.model.playlistMoves

internal object YtMusicPlaylistRepository {
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): Result<String> = YtMusicRepository.call("playlist:create") {
        Innertube.createPlaylist(title, privacy, videoIds = videoIds)
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Map<String, String>> =
        YtMusicRepository.call("playlist:add") { Innertube.addToPlaylist(playlistId, videoIds) }

    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = YtMusicRepository.call("playlist:remove") {
        Innertube.removeFromPlaylist(playlistId, entries)
    }

    suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit> = YtMusicRepository.call("playlist:reorder") {
        playlistMoves(current, target).chunked(MOVE_BATCH).forEach { batch ->
            Innertube.movePlaylistItems(playlistId, batch)
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        YtMusicRepository.call("playlist:rename") { Innertube.renamePlaylist(playlistId, title) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        YtMusicRepository.call("playlist:delete") { Innertube.deletePlaylist(playlistId) }

    private const val MOVE_BATCH = 50
}
