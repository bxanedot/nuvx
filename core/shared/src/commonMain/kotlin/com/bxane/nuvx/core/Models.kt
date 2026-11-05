package com.bxane.nuvx.core

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val localUri: String? = null,
    /** YouTube Music browse IDs for the first credited artist and the album; null when the source gives no link. */
    val artistId: String? = null,
    val albumId: String? = null,
    val mediaKind: MediaKind = MediaKind.SONG,
    /** Paired video source for an audio song; [videoId] remains the stable song/queue identity. */
    val pairedVideoId: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")
    val isLocal: Boolean get() = videoId.startsWith("local:")
    val playbackVideoId: String get() = if (mediaKind == MediaKind.VIDEO) pairedVideoId ?: videoId else videoId
}

@Serializable
enum class MediaKind { SONG, VIDEO }

/** Primitive metadata fields survive Media3 controllers, which otherwise collapse artists and omit duration. */
fun Track.toMetadataExtras(): Map<String, String> = buildMap {
    put("nuvx.artist.count", artists.size.toString())
    artists.forEachIndexed { index, artist -> put("nuvx.artist.$index", artist) }
    album?.let { put("nuvx.album", it) }
    durationMs?.let { put("nuvx.durationMs", it.toString()) }
    localUri?.let { put("nuvx.localUri", it) }
    artistId?.let { put("nuvx.artistId", it) }
    albumId?.let { put("nuvx.albumId", it) }
    put("nuvx.mediaKind", mediaKind.name)
    pairedVideoId?.let { put("nuvx.pairedVideoId", it) }
}

fun trackFromMetadata(
    videoId: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUrl: String?,
    extras: Map<String, String>,
): Track {
    val count = extras["nuvx.artist.count"]?.toIntOrNull()?.takeIf { it in 0..100 }
    val artists = if (count == null) listOfNotNull(artist?.takeIf(String::isNotBlank))
    else List(count) { extras["nuvx.artist.$it"].orEmpty() }
    return Track(
        videoId, title, artists,
        extras["nuvx.album"] ?: album,
        extras["nuvx.durationMs"]?.toLongOrNull(),
        artworkUrl,
        extras["nuvx.localUri"],
        extras["nuvx.artistId"],
        extras["nuvx.albumId"],
        extras["nuvx.mediaKind"]?.let { value -> MediaKind.entries.firstOrNull { it.name == value } } ?: MediaKind.SONG,
        extras["nuvx.pairedVideoId"],
    )
}

/** A browsable search hit that is not directly playable. */
@Serializable
data class Collection(val kind: Kind, val browseId: String, val title: String, val subtitle: String?, val artworkUrl: String?) {
    enum class Kind { ALBUM, ARTIST, PLAYLIST }
}

data class SearchResults(val tracks: List<Track>, val collections: List<Collection>)

/** One extractable adaptive video stream. Signed addresses and headers must never be logged or persisted. */
data class VideoStream(
    val videoId: String,
    val audioUrl: String,
    val videoUrl: String,
    val headers: Map<String, String>,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val audioMimeType: String?,
    val codecs: String?,
    val availableHeights: List<Int>,
    val backend: BackendId,
) {
    override fun toString() = "VideoStream(videoId=$videoId, resolution=${width ?: "?"}x${height ?: "?"}, backend=$backend)"
}

/** A resolved, directly fetchable audio stream. [headers] must accompany every media request and must never be logged. */
class AudioStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val loudnessDb: Double?,
    val backend: BackendId,
    val codecs: String? = null,
    val sampleRateHz: Int? = null,
) {
    override fun toString() = "AudioStream(videoId=$videoId, mime=$mimeType, kbps=$bitrateKbps, backend=$backend)"
}

/** A page of radio tracks; pass [continuation] back to extend the station. */
data class RadioPage(val tracks: List<Track>, val continuation: String?)
