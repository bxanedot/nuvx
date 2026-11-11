package com.bxane.nuvx.playback

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.LikeState
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.BrowseItem
import com.bxane.nuvx.data.model.NOTIFICATION_ART_PX
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.artworkAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
private const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
internal const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
internal const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4
private const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
private const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20
private const val MAX_AUTO_PAGE_SIZE = 50

@UnstableApi
internal class AutoBrowseCatalog(
    private val scope: CoroutineScope,
    private val cacheSong: (Song) -> Unit,
) {
    private val searchResults = ConcurrentHashMap<String, List<Song>>()
    private val searchBrowseResults = ConcurrentHashMap<String, List<BrowseItem>>()

    fun cachedSearchSongs(query: String): List<Song>? = searchResults[query]

    fun cachedSearchResults(query: String): Pair<List<Song>, List<BrowseItem>>? {
        val cachedSongs = searchResults[query]
        val cachedBrowses = searchBrowseResults[query]
        return if (cachedSongs != null || cachedBrowses != null) {
            cachedSongs.orEmpty() to cachedBrowses.orEmpty()
        } else {
            null
        }
    }

    suspend fun searchAll(query: String): Pair<List<Song>, List<BrowseItem>> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList<Song>() to emptyList()
        val results = try {
            withTimeoutOrNull(3_500L) {
                YtMusicRepository.search(safeQuery, SearchFilter.ALL).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val songs = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.distinctBy { it.videoId }
        val browses = results.mapNotNull { (it as? SearchResult.Browse)?.item }
            .distinctBy { it.browseId }
        return songs to browses
    }

    fun storeSearchResults(query: String, songs: List<Song>, browses: List<BrowseItem>) {
        while (searchResults.size >= MAX_AUTO_SEARCH_CACHE_ENTRIES) {
            val key = searchResults.keys.firstOrNull() ?: break
            searchResults.remove(key)
            searchBrowseResults.remove(key)
        }
        searchResults[query] = songs
        searchBrowseResults[query] = browses
        songs.forEach(cacheSong)
    }

    private var cachedRecents: Pair<Long, List<Song>>? = null
    private val recentsMutex = Mutex()
    private val RECENTS_CACHE_TTL_MS = 30_000L

    suspend fun cachedRecentsSongs(): List<Song> = recentsMutex.withLock {
        cachedRecents?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < RECENTS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.recents().getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedRecents = SystemClock.elapsedRealtime() to songs
            songs.forEach(cacheSong)
        }
        songs
    }

    private var cachedQuickPicks: Pair<Long, List<Song>>? = null
    private val quickPicksMutex = Mutex()
    private val QUICK_PICKS_CACHE_TTL_MS = 60_000L

    suspend fun cachedQuickPicksSongs(): List<Song> = quickPicksMutex.withLock {
        cachedQuickPicks?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < QUICK_PICKS_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val recentsIds = cachedRecents?.second?.mapTo(HashSet()) { it.videoId } ?: emptySet()
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.quickPicks(excludeSongIds = recentsIds).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedQuickPicks = SystemClock.elapsedRealtime() to songs
            songs.forEach(cacheSong)
        }
        songs
    }

    private var cachedLiked: Pair<Long, List<Song>>? = null
    private var cachedLikedCookie: String? = null
    private val likedMutex = Mutex()
    private val LIKED_CACHE_TTL_MS = 60_000L

    suspend fun cachedLikedSongs(): List<Song> = likedMutex.withLock {
        val cookie = com.bxane.nuvx.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedLiked = null
            cachedLikedCookie = null
            return emptyList()
        }
        if (cachedLikedCookie != null && cachedLikedCookie != cookie) {
            cachedLiked = null
        }
        cachedLiked?.let { (at, songs) ->
            if (SystemClock.elapsedRealtime() - at < LIKED_CACHE_TTL_MS && songs.isNotEmpty()) return songs
        }
        val songs = try {
            withTimeoutOrNull(4000L) {
                YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()?.songs?.ifEmpty { null }
                    ?: YtMusicRepository.browseSongs("LM").getOrNull()?.songs?.ifEmpty { null }
                    ?: run {
                        val libPlaylists = YtMusicRepository.libraryPlaylists().getOrNull()
                        val likedCard = libPlaylists?.firstOrNull {
                            it.browseId == "VLLM" || it.title.contains("liked", ignoreCase = true)
                        }
                        val likedBrowseId = likedCard?.browseId
                        if (likedBrowseId != null) {
                            YtMusicRepository.browseSongs(likedBrowseId).getOrNull()?.songs
                        } else null
                    }
                    ?: YtMusicRepository.browseSongs("FEmusic_liked_videos").getOrNull()?.songs
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (songs.isNotEmpty()) {
            cachedLiked = SystemClock.elapsedRealtime() to songs
            cachedLikedCookie = cookie
            songs.forEach(cacheSong)
            LikeState.seedLiked(songs.mapTo(HashSet()) { it.videoId })
        }
        songs
    }

    private var cachedHome: Pair<Long, com.bxane.nuvx.data.model.HomeFeed>? = null
    private val homeMutex = Mutex()
    private val HOME_CACHE_TTL_MS = 60_000L
    private val HOME_NETWORK_TIMEOUT_MS = 2_500L

    suspend fun cachedHomeFeed(): com.bxane.nuvx.data.model.HomeFeed? = homeMutex.withLock {
        cachedHome?.let { (at, feed) ->
            if (SystemClock.elapsedRealtime() - at < HOME_CACHE_TTL_MS) return feed
        }
        val feed = try {
            withTimeoutOrNull(HOME_NETWORK_TIMEOUT_MS) {
                YtMusicRepository.home().getOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        cachedHome = SystemClock.elapsedRealtime() to feed
        feed
    }

    private var cachedPlaylistCards: Pair<Long, List<ShelfItem>>? = null
    private var cachedPlaylistCookie: String? = null
    private val playlistsMutex = Mutex()
    private val PLAYLISTS_CACHE_TTL_MS = 60_000L

    private suspend fun fetchPlaylistCards(): List<ShelfItem> = try {
        withTimeoutOrNull(3_000L) {
            YtMusicRepository.libraryPlaylists().getOrNull()
                ?: YtMusicRepository.userPlaylists().getOrNull()?.map {
                    ShelfItem(
                        title = it.title,
                        subtitle = it.subtitle,
                        thumbnailUrl = it.thumbnailUrl,
                        videoId = null,
                        browseId = "VL${it.playlistId}",
                    )
                }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun cachedPlaylistCards(): List<ShelfItem> = playlistsMutex.withLock {
        val cookie = com.bxane.nuvx.data.innertube.Innertube.cookie
        if (cookie == null) {
            cachedPlaylistCards = null
            cachedPlaylistCookie = null
            return emptyList()
        }
        if (cachedPlaylistCookie != null && cachedPlaylistCookie != cookie) {
            cachedPlaylistCards = null
        }
        cachedPlaylistCards?.let { (at, cards) ->
            if (SystemClock.elapsedRealtime() - at < PLAYLISTS_CACHE_TTL_MS && cards.isNotEmpty()) return cards
        }
        val cards = fetchPlaylistCards()
        if (cards.isNotEmpty()) {
            cachedPlaylistCards = SystemClock.elapsedRealtime() to cards
            cachedPlaylistCookie = cookie
        }
        cards
    }

    fun snapshotRecents(): List<Song> = cachedRecents?.second.orEmpty()
    fun snapshotQuickPicks(): List<Song> = cachedQuickPicks?.second.orEmpty()
    fun snapshotLiked(): List<Song> {
        val cookie = com.bxane.nuvx.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedLikedCookie) cachedLiked?.second.orEmpty() else emptyList()
    }
    fun snapshotPlaylistCards(): List<ShelfItem> {
        val cookie = com.bxane.nuvx.data.innertube.Innertube.cookie
        return if (cookie != null && cookie == cachedPlaylistCookie) cachedPlaylistCards?.second.orEmpty() else emptyList()
    }
    fun snapshotHome(): com.bxane.nuvx.data.model.HomeFeed? = cachedHome?.second

    private fun isFresh(at: Long, ttlMs: Long): Boolean =
        SystemClock.elapsedRealtime() - at < ttlMs

    fun isRecentsFresh(): Boolean =
        cachedRecents?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, RECENTS_CACHE_TTL_MS) } ?: false

    fun isQuickPicksFresh(): Boolean =
        cachedQuickPicks?.let { (at, songs) -> songs.isNotEmpty() && isFresh(at, QUICK_PICKS_CACHE_TTL_MS) } ?: false

    fun isLikedFresh(): Boolean =
        snapshotLiked().isNotEmpty() && cachedLiked?.let { (at, _) -> isFresh(at, LIKED_CACHE_TTL_MS) } == true

    fun isPlaylistsFresh(): Boolean =
        snapshotPlaylistCards().isNotEmpty() && cachedPlaylistCards?.let { (at, _) -> isFresh(at, PLAYLISTS_CACHE_TTL_MS) } == true

    fun refreshFolderInBackground(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
        fetch: suspend () -> Int,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val freshCount = fetch()
                if (freshCount > 0) {
                    session.notifyChildrenChanged(browser, parentId, freshCount, params)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    fun shelfToMediaItemOrNull(item: ShelfItem): MediaItem? {
        val videoId = item.videoId
        if (videoId != null) {
            val song = Song(
                videoId = videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
            )
            cacheSong(song)
            return song.toMediaItem()
        }
        val browseId = item.browseId
        if (browseId != null) {
            return MediaItem.Builder()
                .setMediaId("browse:$browseId")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setSubtitle(item.subtitle)
                        .setArtworkUri(item.thumbnailUrl.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                        .build(),
                )
                .build()
        }
        return null
    }

    fun shelfToSongOrNull(item: ShelfItem): Song? {
        val id = item.videoId ?: return null
        return Song(
            videoId = id,
            title = item.title,
            artist = InnertubeParser.artistFromSubtitle(item.subtitle),
            thumbnailUrl = item.thumbnailUrl,
        )
    }

    fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

    fun shelfToGridPlaylistItem(playlist: ShelfItem): MediaItem {
        val browseId = playlist.browseId ?: "VL${playlist.videoId}"
        val mediaId = if (browseId.startsWith("VL")) "playlist:${browseId.removePrefix("VL")}" else "browse:$browseId"
        return createGridPlaylistItem(
            mediaId = mediaId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            artworkUrl = playlist.thumbnailUrl,
        )
    }

    fun browseItemToGridItem(item: BrowseItem): MediaItem = createGridPlaylistItem(
        mediaId = "browse:${item.browseId}",
        title = item.title,
        subtitle = item.subtitle,
        artworkUrl = item.thumbnailUrl,
        folderType = if (item.browseId.startsWith("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    )

    fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }
}
