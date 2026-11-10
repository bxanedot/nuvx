package com.bxane.nuvx.data.model

enum class QueueTier {
    USER_QUEUE,
    CONTEXT,
    AUTOPLAY,
}

data class ArtistRef(
    val name: String,
    val browseId: String? = null,
)

data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String? = null,
    val artistId: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val isVideoOrigin: Boolean = isVideo,
    val setVideoId: String? = null,
    val queueTier: QueueTier = QueueTier.CONTEXT,
    val queueEntryId: String? = null,
    val radioName: String? = null,
    val localUri: String? = null,
    val downloadFormat: String? = null,
    val localPath: String? = null,
    val localDateAddedSeconds: Long? = null,
    val localDateModifiedSeconds: Long? = null,
    val trackNumber: Int? = null,
    val sourceQuality: String? = null,
    val sourceHiRes: Boolean = false,
    val isExplicit: Boolean? = null,
    val playbackSource: String? = null,
    val playbackSourceType: PlaybackSourceType? = null,
    val playbackSourceId: String? = null,
    val feedbackToken: String? = null,
) {
    val fromAutoplay: Boolean get() = queueTier == QueueTier.AUTOPLAY

    constructor(
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        durationText: String? = null,
        artistId: String? = null,
        artists: List<ArtistRef> = emptyList(),
        albumId: String? = null,
        albumName: String? = null,
        isVideo: Boolean = false,
        isVideoOrigin: Boolean = isVideo,
        setVideoId: String? = null,
        fromAutoplay: Boolean,
        radioName: String? = null,
        localUri: String? = null,
        downloadFormat: String? = null,
        localPath: String? = null,
        localDateAddedSeconds: Long? = null,
        localDateModifiedSeconds: Long? = null,
        trackNumber: Int? = null,
        sourceQuality: String? = null,
        isExplicit: Boolean? = null,
        playbackSource: String? = null,
        playbackSourceType: PlaybackSourceType? = null,
        playbackSourceId: String? = null,
    ) : this(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        artists = artists,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        isVideoOrigin = isVideoOrigin,
        setVideoId = setVideoId,
        queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.CONTEXT,
        queueEntryId = null,
        radioName = radioName,
        localUri = localUri,
        downloadFormat = downloadFormat,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
        sourceQuality = sourceQuality,
        isExplicit = isExplicit,
        playbackSource = playbackSource,
        playbackSourceType = playbackSourceType,
        playbackSourceId = playbackSourceId,
    )
}

fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)

fun Song.isSameTrackAs(other: Song?): Boolean {
    other ?: return false
    return title == other.title && artist == other.artist
}

fun playingRowMatcher(rows: List<Song>, current: Song?): (Song) -> Boolean {
    current ?: return { false }
    val sameId = rows.filter { it.videoId == current.videoId }
    if (sameId.isNotEmpty()) {
        val slot = current.setVideoId
        if (slot != null && sameId.any { it.setVideoId == slot }) {
            return { it.videoId == current.videoId && it.setVideoId == slot }
        }
        return { it.videoId == current.videoId }
    }
    val byName = rows.firstOrNull { it.isSameTrackAs(current) } ?: return { false }
    return { it == byName }
}

fun Song.durationMillis(): Long = durationText.durationMillis()

fun String?.durationMillis(): Long {
    val parts = this?.trim()?.takeIf { it.isNotEmpty() }?.split(":") ?: return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
        else -> return 0L
    }
    return (seconds * 1_000).coerceAtLeast(0L)
}

fun String?.artworkAt(px: Int): String? {
    if (this == null) return null
    val sized = this.replace(SIZE_HINT, "w$px-h$px")
    return if (isGoogleImageHost(this)) {
        sized.replace(PARAM_SIZE_HINT, "=s$px")
    } else {
        sized
    }
}

private fun isGoogleImageHost(url: String): Boolean {
    val host = url.substringAfter("://", "")
        .ifEmpty { url.substringAfter("//", "") }
        .substringBefore('/')
        .substringBefore(':')
        .substringBefore('?')
        .lowercase()
    return host == "ggpht.com" || host.endsWith(".ggpht.com") ||
        host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
}

private val SIZE_HINT = Regex("""w\d+-h\d+""")
private val PARAM_SIZE_HINT = Regex("""=s\d+""")

const val ROW_ART_PX = 160

const val CARD_ART_PX = 480

const val HEADER_ART_PX = 720

const val NOTIFICATION_ART_PX = 544

const val PLAYER_ART_PX = 1200

enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }

enum class PlaybackSourceType {
    HOME,
    SEARCH,
    HISTORY,
    REPLAY,
    EXPLORE,
    BROWSE,
    SHARED_LINK,
    QUEUE,
}

const val LIVE_RADIO_PREFIX = "radio:"

fun isLiveRadio(mediaId: String?): Boolean = mediaId?.startsWith(LIVE_RADIO_PREFIX) == true

data class BrowseItem(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)

sealed interface SearchResult {
    data class TopTrack(val song: Song) : SearchResult
    data class Track(val song: Song) : SearchResult
    data class Browse(val item: BrowseItem) : SearchResult
}

enum class SearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    VIDEOS("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA=="),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgWKAQIoAWoKEAkQChAFEAMQBA=="),

    YOUTUBE("YouTube", null),
}

data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
    val isVideo: Boolean = false,
)

enum class ShelfLayout {
    CARDS,

    LIST,

    VIDEO,
}

data class Account(
    val name: String,
    val email: String,
    val thumbnailUrl: String?,
)

data class AccountChannel(
    val name: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val pageId: String?,
    val dataSyncId: String?,
    val activeOnWeb: Boolean,
) {
    val key: String get() = pageId ?: dataSyncId ?: name
}

data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    val subtitle: String = "",
    val moreBrowseId: String? = null,
    val moreParams: String? = null,
    val layout: ShelfLayout = ShelfLayout.CARDS,
)

fun List<HomeShelf>.withoutVideos(): List<HomeShelf> = mapNotNull { shelf ->
    if (shelf.layout == ShelfLayout.VIDEO) return@mapNotNull null
    val items = shelf.items.filterNot { it.isVideo }
    if (items.isEmpty()) null else shelf.copy(items = items)
}

fun shelfLayoutFor(title: String, items: List<ShelfItem>, fromList: Boolean): ShelfLayout {
    if (items.isEmpty()) return ShelfLayout.CARDS
    val first = items.first()
    if (fromList && first.videoId != null) return ShelfLayout.LIST
    if (title.trim().equals("Listen again", ignoreCase = true)) return ShelfLayout.LIST
    if (items.count { it.isVideo } * 2 > items.size) return ShelfLayout.VIDEO
    return ShelfLayout.CARDS
}

fun List<HomeShelf>.withoutRepeatsOf(existing: List<HomeShelf>): List<HomeShelf> {
    val shown = existing.toMutableList()
    return filter { shelf ->
        val keys = shelf.browseKeys()
        val repeat = keys.isNotEmpty() && shown.any { other ->
            val shared = other.browseKeys().count { it in keys }
            shared > 0 && shared >= REPEAT_SHELF_SHARE * minOf(shelf.items.size, other.items.size)
        }
        if (!repeat) shown += shelf
        !repeat
    }
}

private const val REPEAT_SHELF_SHARE = 0.6

private fun HomeShelf.browseKeys(): Set<String> = items.mapNotNullTo(HashSet()) { it.browseId }

data class HomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String?,
)

data class MoodGenreSection(
    val title: String,
    val items: List<MoodGenre>,
)

data class MoodGenre(
    val title: String,
    val browseId: String,
    val params: String?,
    val thumbnailUrl: String? = null,
    val stripeColor: Long? = null,
)

data class LibraryPage(
    val likedSongs: List<Song>,
    val librarySongs: List<Song>,
    val shelves: List<HomeShelf>,
    val likedContinuation: String? = null,
) {
    val isEmpty: Boolean
        get() = likedSongs.isEmpty() && librarySongs.isEmpty() && shelves.isEmpty()
}

data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: UiState<List<Song>>,
    val type: BrowseType = BrowseType.OTHER,
    val continuation: String? = null,
    val sections: List<HomeShelf> = emptyList(),
    val suggestedSongs: List<Song> = emptyList(),
    val library: LibraryState? = null,
    val description: String? = null,
    val subscriberCountText: String? = null,
    val monthlyListenerCount: String? = null,
    val subscription: SubscriptionState? = null,
)

data class LibraryState(
    val playlistId: String,
    val saved: Boolean,
)

fun List<HomeShelf>.catalogReleases(): List<ShelfItem> =
    flatMap { it.items }.filter { it.browseId?.startsWith("MPRE") == true }.distinctBy { it.browseId }

data class SubscriptionState(
    val channelId: String,
    val subscribed: Boolean,
)

data class ArtistPage(
    val songs: List<Song>,
    val moreSongsBrowseId: String?,
    val sections: List<HomeShelf>,
    val thumbnailUrl: String? = null,
    val name: String? = null,
    val description: String? = null,
    val subscriberCountText: String? = null,
    val monthlyListenerCount: String? = null,
    val subscription: SubscriptionState? = null,
    val radio: WatchTarget? = null,
    val shuffle: WatchTarget? = null,
)

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

enum class PlaylistPrivacy(val label: String, val apiValue: String) {
    PRIVATE("Private", "PRIVATE"),
    UNLISTED("Unlisted", "UNLISTED"),
    PUBLIC("Public", "PUBLIC"),
}

data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
) {
    val browseId: String get() = "VL$playlistId"
}

data class SongMenu(
    val likeStatus: LikeStatus?,
    val inLibrary: Boolean,
    val addToLibraryToken: String?,
    val removeFromLibraryToken: String?,
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
