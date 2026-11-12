package com.bxane.nuvx.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.bxane.nuvx.R
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.download.Downloads
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class AutoBrowseMediaLibraryCallbacks(
    private val context: Context,
    private val scope: CoroutineScope,
    private val autoBrowse: AutoBrowseCatalog,
    private val autoMediaItemResolver: AutoMediaItemResolver,
    private val player: () -> ExoPlayer?,
    private val songCache: ConcurrentHashMap<String, Song>,
) {
    private fun createLoginPromptItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("msg:login_required")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.auto_login_title))
                    .setSubtitle(context.getString(R.string.auto_login_subtitle))
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val isRecent = params?.isRecent == true ||
            params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
            params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

        val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
        val rootExtras = autoBrowse.buildContentStyleBundle(
            CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
        )
        val rootItem = MediaItem.Builder()
            .setMediaId(rootId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .setExtras(rootExtras)
                    .build(),
            )
            .build()
        val rootParams = LibraryParams.Builder()
            .setExtras(rootExtras)
            .build()
        scope.launch(Dispatchers.IO) {
            try {
                autoBrowse.cachedHomeFeed()
            } catch (_: Exception) {}
        }

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
    }

    fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
        val items: List<MediaItem> = when (parentId) {
            MEDIA_ROOT_ID -> listOf(
                autoBrowse.createFolderItem(MEDIA_QUICK_PICKS_ID, context.getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                autoBrowse.createFolderItem(MEDIA_RECENTS_ID, context.getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                autoBrowse.createFolderItem(MEDIA_PLAYLISTS_ID, context.getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                autoBrowse.createFolderItem(MEDIA_LIKED_ID, context.getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                autoBrowse.createFolderItem(MEDIA_MORE_ID, context.getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_MORE_ID -> listOf(
                autoBrowse.createFolderItem(MEDIA_DOWNLOADS_ID, context.getString(R.string.downloads), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                autoBrowse.createFolderItem(MEDIA_LOCAL_MUSIC_ID, context.getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_RECENTS_ID -> {
                val snapshot = autoBrowse.snapshotRecents()
                val songs = if (snapshot.isNotEmpty()) {
                    if (!autoBrowse.isRecentsFresh()) {
                        autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                            autoBrowse.cachedRecentsSongs().size
                        }
                    }
                    snapshot
                } else {
                    autoBrowse.cachedRecentsSongs()
                }
                songs.forEach { songCache[it.videoId] = it }
                if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                    autoBrowse.createInfoItem(
                        "msg:empty_recents",
                        context.getString(R.string.auto_empty_recents_title),
                        context.getString(R.string.auto_empty_recents_subtitle),
                    ),
                )
            }
            MEDIA_QUICK_PICKS_ID -> {
                val snapshot = autoBrowse.snapshotQuickPicks()
                val songs = if (snapshot.isNotEmpty()) {
                    if (!autoBrowse.isQuickPicksFresh()) {
                        autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                            autoBrowse.cachedQuickPicksSongs().size
                        }
                    }
                    snapshot
                } else {
                    autoBrowse.cachedQuickPicksSongs().ifEmpty {
                        autoBrowse.snapshotHome()?.shelves?.flatMap { it.items }
                            ?.mapNotNull { autoBrowse.shelfToSongOrNull(it) }
                            ?.distinctBy { it.videoId }
                            .orEmpty()
                    }
                }
                songs.forEach { songCache[it.videoId] = it }
                if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                    autoBrowse.createInfoItem(
                        "msg:empty_quick_picks",
                        context.getString(R.string.auto_empty_quick_picks_title),
                        context.getString(R.string.auto_empty_quick_picks_subtitle),
                    ),
                )
            }
            MEDIA_LIKED_ID -> {
                if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
                    listOf(createLoginPromptItem())
                } else {
                    val snapshot = autoBrowse.snapshotLiked()
                    val songs = if (snapshot.isNotEmpty()) {
                        if (!autoBrowse.isLikedFresh()) {
                            autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                autoBrowse.cachedLikedSongs().size
                            }
                        }
                        snapshot
                    } else {
                        autoBrowse.cachedLikedSongs()
                    }
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) {
                        songs.map { it.toMediaItem() }
                    } else {
                        listOf(
                            autoBrowse.createInfoItem(
                                "msg:empty_liked",
                                context.getString(R.string.auto_empty_liked_title),
                                context.getString(R.string.auto_empty_liked_subtitle),
                            ),
                        )
                    }
                }
            }
            MEDIA_DOWNLOADS_ID -> {
                val downloaded = Downloads.getDownloadedSongs(context)
                downloaded.forEach { songCache[it.videoId] = it }
                if (downloaded.isNotEmpty()) downloaded.map { it.toMediaItem() } else listOf(
                    autoBrowse.createInfoItem(
                        "msg:empty_downloads",
                        context.getString(R.string.auto_empty_offline_title),
                        context.getString(R.string.auto_empty_offline_subtitle),
                    ),
                )
            }
            MEDIA_PLAYLISTS_ID -> {
                if (com.bxane.nuvx.data.innertube.Innertube.cookie == null) {
                    listOf(createLoginPromptItem())
                } else {
                    val snapshot = autoBrowse.snapshotPlaylistCards()
                    val playlists = if (snapshot.isNotEmpty()) {
                        if (!autoBrowse.isPlaylistsFresh()) {
                            autoBrowse.refreshFolderInBackground(session, browser, parentId, params) {
                                autoBrowse.cachedPlaylistCards().size
                            }
                        }
                        snapshot
                    } else {
                        autoBrowse.cachedPlaylistCards()
                    }
                    if (playlists.isNotEmpty()) playlists.map(autoBrowse::shelfToGridPlaylistItem) else listOf(
                        autoBrowse.createInfoItem(
                            "msg:empty_playlists",
                            context.getString(R.string.auto_empty_playlists_title),
                            context.getString(R.string.auto_empty_playlists_subtitle),
                        ),
                    )
                }
            }
            MEDIA_LOCAL_MUSIC_ID -> {
                val local = LocalMediaRepository.getLocalMusic(context)
                local.forEach { songCache[it.videoId] = it }
                if (local.isNotEmpty()) local.map { it.toMediaItem() } else listOf(
                    autoBrowse.createInfoItem(
                        "msg:empty_local",
                        context.getString(R.string.auto_empty_offline_title),
                        context.getString(R.string.auto_empty_offline_subtitle),
                    ),
                )
            }
            else -> {
                when {
                    parentId.startsWith("playlist:") -> {
                        val playlistId = parentId.removePrefix("playlist:")
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                YtMusicRepository.browseSongs("VL$playlistId").getOrNull()?.songs
                                    ?: YtMusicRepository.browseSongs(playlistId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        songs.map { it.toMediaItem() }
                    }
                    parentId.startsWith("browse:") -> {
                        val browseId = parentId.removePrefix("browse:")
                        val songs = try {
                            withTimeoutOrNull(2500L) {
                                YtMusicRepository.browseSongs(browseId).getOrNull()?.songs
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        songs.forEach { songCache[it.videoId] = it }
                        songs.map { it.toMediaItem() }
                    }
                    else -> {
                        val cachedSong = songCache[parentId]
                        if (cachedSong != null) {
                            listOf(cachedSong.toMediaItem())
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }
        val returnParams = LibraryParams.Builder().setExtras(
            autoBrowse.buildContentStyleBundle(
                if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            ),
        ).build()
        LibraryResult.ofItemList(ImmutableList.copyOf(autoBrowse.paginate(items, page, pageSize)), returnParams)
    }

    fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        val item = when (mediaId) {
            MEDIA_ROOT_ID -> MediaItem.Builder()
                .setMediaId(MEDIA_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(context.getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .build(),
                ).build()
            MEDIA_RECENTS_ID -> autoBrowse.createFolderItem(MEDIA_RECENTS_ID, context.getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_QUICK_PICKS_ID -> autoBrowse.createFolderItem(MEDIA_QUICK_PICKS_ID, context.getString(R.string.auto_quick_picks), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_PLAYLISTS_ID -> autoBrowse.createFolderItem(MEDIA_PLAYLISTS_ID, context.getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
            MEDIA_MORE_ID -> autoBrowse.createFolderItem(MEDIA_MORE_ID, context.getString(R.string.more), folderType = MediaMetadata.FOLDER_TYPE_MIXED)
            MEDIA_LIKED_ID -> autoBrowse.createFolderItem(MEDIA_LIKED_ID, context.getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
            MEDIA_DOWNLOADS_ID -> autoBrowse.createFolderItem(MEDIA_DOWNLOADS_ID, context.getString(R.string.downloads))
            MEDIA_LOCAL_MUSIC_ID -> autoBrowse.createFolderItem(MEDIA_LOCAL_MUSIC_ID, context.getString(R.string.local_music))
            "msg:login_required" -> createLoginPromptItem()
            else -> {
                if (mediaId.startsWith("msg:")) {
                    autoBrowse.createInfoItem(
                        mediaId,
                        context.getString(R.string.auto_unavailable_title),
                        context.getString(R.string.auto_unavailable_subtitle),
                    )
                } else {
                    val cached = songCache[mediaId]
                    if (cached != null) {
                        cached.toMediaItem()
                    } else if (mediaId.startsWith("playlist:") || mediaId.startsWith("browse:")) {
                        autoBrowse.createGridPlaylistItem(
                            mediaId = mediaId,
                            title = if (mediaId.contains("MPREb_")) "Album" else context.getString(R.string.playlists),
                            folderType = if (mediaId.contains("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
                        )
                    } else {
                        autoBrowse.createInfoItem(
                            "msg:unavailable|$mediaId",
                            context.getString(R.string.auto_unavailable_title),
                            context.getString(R.string.auto_unavailable_subtitle),
                        )
                    }
                }
            }
        }
        LibraryResult.ofItem(item, null)
    }

    fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        val activePlayer = player()
        if (activePlayer != null && activePlayer.mediaItemCount > 0) {
            val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
            val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
            val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            return@future MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(currentItems),
                startIndex,
                startPositionMs,
            )
        }

        val downloaded = Downloads.getDownloadedSongs(context)
        if (downloaded.isNotEmpty()) {
            val items = downloaded.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
            return@future MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(items),
                0,
                0L,
            )
        }

        val localSongs = LocalMediaRepository.getLocalMusic(context)
        if (localSongs.isNotEmpty()) {
            val items = localSongs.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
            return@future MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(items),
                0,
                0L,
            )
        }

        val fallbackSongs = try {
            withTimeoutOrNull(2500L) {
                val home = YtMusicRepository.home().getOrNull()
                home?.shelves?.flatMap { shelf ->
                    shelf.items.mapNotNull { autoBrowse.shelfToMediaItemOrNull(it) }
                }?.distinctBy { it.mediaId }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (fallbackSongs.isNotEmpty()) {
            return@future MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(fallbackSongs),
                0,
                0L,
            )
        }

        MediaSession.MediaItemsWithStartPosition(
            ImmutableList.of(),
            0,
            0L,
        )
    }

    fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
        val (songs, browses) = autoBrowse.searchAll(query)
        autoBrowse.storeSearchResults(query, songs, browses)
        session.notifySearchResultChanged(browser, query, songs.size + browses.size, params)
        LibraryResult.ofVoid(params)
    }

    fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val (songs, browses) = autoBrowse.cachedSearchResults(query)
            ?: autoBrowse.searchAll(query).also {
                autoBrowse.storeSearchResults(query, it.first, it.second)
            }
        songs.forEach { songCache[it.videoId] = it }
        val items = songs.map { it.toMediaItem() } + browses.map(autoBrowse::browseItemToGridItem)
        LibraryResult.ofItemList(ImmutableList.copyOf(autoBrowse.paginate(items, page, pageSize)), params)
    }

    fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        val resolved = autoMediaItemResolver.resolve(mediaItems)
        val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
        MediaSession.MediaItemsWithStartPosition(
            ImmutableList.copyOf(resolved),
            validIndex,
            startPositionMs.coerceAtLeast(0L),
        )
    }

    fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
        autoMediaItemResolver.resolve(mediaItems)
    }
}
