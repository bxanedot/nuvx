package com.bxane.nuvx.data.model

import kotlinx.serialization.Serializable








@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
    val fromAutoplay: Boolean = false,












    val isExplicit: Boolean? = null,

    val localPath: String? = null,
    val sourceQuality: String? = null,
)





fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)


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


fun String?.artworkAt(px: Int): String? = this?.replace(SIZE_HINT, "w$px-h$px")

private val SIZE_HINT = Regex("""w\d+-h\d+""")


const val ROW_ART_PX = 160


const val CARD_ART_PX = 480


const val HEADER_ART_PX = 720


const val NOTIFICATION_ART_PX = 544

enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }


fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
    browseId.startsWith("UC") -> BrowseType.ARTIST
    browseId.startsWith("MPREb") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
    browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
    else -> fallback
}


@Serializable
data class BrowseItem(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)












@Serializable
sealed interface SearchResult {
    @Serializable
    data class TopTrack(val song: Song) : SearchResult

    @Serializable
    data class Track(val song: Song) : SearchResult

    @Serializable
    data class Browse(val item: BrowseItem) : SearchResult
}

enum class SearchFilter(val label: String, val params: String?) {







    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    VIDEOS("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA=="),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgWKAQIoAWoKEAkQChAFEAMQBA=="),
}


@Serializable
data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String? = null,
    val browseId: String? = null,
    val isVideo: Boolean = false,
)

@Serializable
data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    val subtitle: String = "",
)


@Serializable
data class HomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String? = null,
)


@Serializable
data class MoodGenreSection(
    val title: String,
    val items: List<MoodGenre>,
)















@Serializable
data class MoodGenre(
    val title: String,
    val browseId: String,
    val params: String? = null,
    val thumbnailUrl: String? = null,

    val stripeColor: Long? = null,
)


@Serializable
data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    val type: BrowseType = BrowseType.OTHER,
    val sections: List<HomeShelf> = emptyList(),
    val description: String? = null,
    val subscriberCountText: String? = null,
    val monthlyListenerCount: String? = null,
    val subscription: ArtistSubscriptionState? = null,

    val continuation: String? = null,
    val suggestedSongs: List<Song> = emptyList(),
    val libraryPlaylistId: String? = null,
    val librarySaved: Boolean? = null,
    val playlistOwned: Boolean? = null,
)


@Serializable
data class ArtistSubscriptionState(
    val channelId: String,
    val subscribed: Boolean,
)


@Serializable
data class SongBatch(
    val songs: List<Song>,
    val continuation: String? = null,
)





@Serializable
data class ResolvedStream(
    val url: String,
    val kbps: Int,
    val mimeType: String,
)


@Serializable
data class Account(
    val name: String,
    val email: String,
    val photoUrl: String? = null,
)





@Serializable
data class LibraryState(
    val playlistId: String,
    val saved: Boolean,
)

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }


enum class PlaylistPrivacy(val label: String, val apiValue: String) {
    PRIVATE("Private", "PRIVATE"),
    UNLISTED("Unlisted", "UNLISTED"),
    PUBLIC("Public", "PUBLIC"),
}


@Serializable
data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
) {
    val browseId: String get() = "VL$playlistId"
}


@Serializable
data class SongMenu(
    val likeStatus: String? = null,
    val inLibrary: Boolean = false,
    val addToLibraryToken: String? = null,
    val removeFromLibraryToken: String? = null,
)


@Serializable
data class SearchHit(
    val kind: String,
    val videoId: String? = null,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val browseId: String? = null,
    val browseType: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,

    val sourceQuality: String? = null,
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
