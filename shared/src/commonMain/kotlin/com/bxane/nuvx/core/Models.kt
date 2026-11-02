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
) {
    val artistLine: String get() = artists.joinToString(", ")
}

/** A browsable search hit that is not directly playable. */
@Serializable
data class Collection(val kind: Kind, val browseId: String, val title: String, val subtitle: String?, val artworkUrl: String?) {
    enum class Kind { ALBUM, ARTIST, PLAYLIST }
}

data class SearchResults(val tracks: List<Track>, val collections: List<Collection>)

/** A resolved, directly fetchable audio stream. [headers] must accompany every media request and must never be logged. */
class AudioStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val loudnessDb: Double?,
    val backend: BackendId,
) {
    override fun toString() = "AudioStream(videoId=$videoId, mime=$mimeType, kbps=$bitrateKbps, backend=$backend)"
}

/** A page of radio tracks; pass [continuation] back to extend the station. */
data class RadioPage(val tracks: List<Track>, val continuation: String?)
