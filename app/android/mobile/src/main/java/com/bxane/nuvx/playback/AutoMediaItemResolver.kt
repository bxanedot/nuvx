package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.download.Downloads
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal const val MEDIA_ROOT_ID = "root"
internal const val MEDIA_RECENTS_ID = "recents"
internal const val MEDIA_QUICK_PICKS_ID = "quick_picks"
internal const val MEDIA_PLAYLISTS_ID = "playlists"
internal const val MEDIA_MORE_ID = "more"
internal const val MEDIA_LIKED_ID = "liked"
internal const val MEDIA_DOWNLOADS_ID = "downloads"
internal const val MEDIA_LOCAL_MUSIC_ID = "local_music"

@UnstableApi
internal class AutoMediaItemResolver(
    private val context: Context,
    private val autoBrowse: AutoBrowseCatalog,
    private val songCache: ConcurrentHashMap<String, Song>,
) {
    suspend fun resolve(mediaItems: List<MediaItem>): List<MediaItem> {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            val id = item.mediaId
            val searchQuery = item.requestMetadata.searchQuery
            when {
                !searchQuery.isNullOrBlank() -> {
                    val cached = autoBrowse.cachedSearchSongs(searchQuery)
                    val songs = if (!cached.isNullOrEmpty()) {
                        cached
                    } else {
                        try {
                            withTimeoutOrNull(3000L) {
                                YtMusicRepository.search(searchQuery, SearchFilter.SONGS).getOrNull()
                                    ?.mapNotNull { if (it is SearchResult.Track) it.song else null }
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) {
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                }
                id.startsWith("playlist:") -> {
                    val playlistId = id.removePrefix("playlist:")
                    val songs = try {
                        withTimeoutOrNull(3000L) {
                            YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id.startsWith("browse:") -> {
                    val browseId = id.removePrefix("browse:")
                    val songs = try {
                        withTimeoutOrNull(3000L) {
                            YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_ROOT_ID || id == MEDIA_RECENTS_ID -> {
                    val songs = autoBrowse.cachedRecentsSongs()
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_QUICK_PICKS_ID -> {
                    val songs = autoBrowse.cachedQuickPicksSongs()
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_LIKED_ID -> {
                    val songs = autoBrowse.cachedLikedSongs()
                    if (songs.isNotEmpty()) {
                        resolved.addAll(songs.map { it.toMediaItem() })
                    }
                }
                id == MEDIA_DOWNLOADS_ID -> {
                    val downloaded = Downloads.getDownloadedSongs(context)
                    downloaded.forEach { songCache[it.videoId] = it }
                    resolved.addAll(downloaded.map { it.toMediaItem() })
                }
                id == MEDIA_PLAYLISTS_ID -> {
                    val songs = try {
                        withTimeoutOrNull(2500L) {
                            val playlists = YtMusicRepository.userPlaylists().getOrNull() ?: emptyList()
                            val first = playlists.firstOrNull()
                            if (first != null) {
                                YtMusicRepository.browseSongs("VL${first.playlistId}").getOrNull()?.songs
                                    ?: YtMusicRepository.browseSongs(first.playlistId).getOrNull()?.songs
                            } else null
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    resolved.addAll(songs.map { it.toMediaItem() })
                }
                id == MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(context)
                    local.forEach { songCache[it.videoId] = it }
                    resolved.addAll(local.map { it.toMediaItem() })
                }
                songCache.containsKey(id) -> {
                    resolved.add(songCache[id]!!.toMediaItem())
                }
                item.localConfiguration != null -> {
                    resolved.add(item)
                }
                item.requestMetadata.mediaUri != null -> {
                    val uri = item.requestMetadata.mediaUri!!
                    val videoId = uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()
                    if (videoId.isNotEmpty() && !videoId.startsWith("http")) {
                        val cached = songCache[videoId]
                        if (cached != null) {
                            resolved.add(cached.toMediaItem())
                        } else {
                            resolved.add(Song(videoId = videoId, title = item.mediaMetadata.title?.toString() ?: "Track", artist = item.mediaMetadata.artist?.toString() ?: "Artist", thumbnailUrl = null).toMediaItem())
                        }
                    } else {
                        resolved.add(item.buildUpon().setUri(uri).build())
                    }
                }
                else -> {
                    val cached = songCache[id]
                    if (cached != null) {
                        resolved.add(cached.toMediaItem())
                    } else {
                        val song = item.toSong()
                        resolved.add(song.toMediaItem())
                    }
                }
            }
        }
        return if (resolved.isEmpty()) mediaItems else resolved
    }
}
