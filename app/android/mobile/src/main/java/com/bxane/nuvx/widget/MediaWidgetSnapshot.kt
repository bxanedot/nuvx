package com.bxane.nuvx.widget

import android.content.Context

internal data class MediaWidgetSnapshot(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val isLiked: Boolean = false,
    val canLike: Boolean = false,
    val shuffleEnabled: Boolean = false,
) {
    val hasTrack: Boolean get() = mediaId != null

    companion object {

        val EMPTY = MediaWidgetSnapshot(
            mediaId = null,
            title = "",
            artist = "",
            artworkUrl = null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false,
        )

        fun save(context: Context, snapshot: MediaWidgetSnapshot) {
            prefs(context).edit()
                .putString(KEY_MEDIA_ID, snapshot.mediaId)
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_ARTIST, snapshot.artist)
                .putString(KEY_ARTWORK, snapshot.artworkUrl)
                .putBoolean(KEY_PLAYING, snapshot.isPlaying)
                .putBoolean(KEY_HAS_PREVIOUS, snapshot.hasPrevious)
                .putBoolean(KEY_HAS_NEXT, snapshot.hasNext)
                .putBoolean(KEY_LIKED, snapshot.isLiked)
                .putBoolean(KEY_CAN_LIKE, snapshot.canLike)
                .putBoolean(KEY_SHUFFLE, snapshot.shuffleEnabled)
                .apply()
        }

        fun load(context: Context): MediaWidgetSnapshot {
            val prefs = prefs(context)
            val mediaId = prefs.getString(KEY_MEDIA_ID, null)
            if (mediaId != null) {
                return MediaWidgetSnapshot(
                    mediaId = mediaId,
                    title = prefs.getString(KEY_TITLE, "").orEmpty(),
                    artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
                    artworkUrl = prefs.getString(KEY_ARTWORK, null),
                    isPlaying = prefs.getBoolean(KEY_PLAYING, false),
                    hasPrevious = prefs.getBoolean(KEY_HAS_PREVIOUS, false),
                    hasNext = prefs.getBoolean(KEY_HAS_NEXT, false),
                    isLiked = prefs.getBoolean(KEY_LIKED, false),
                    canLike = prefs.getBoolean(KEY_CAN_LIKE, false),
                    shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
                )
            }
            return EMPTY
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("nuvx_widget", Context.MODE_PRIVATE)

        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ARTWORK = "artwork"
        private const val KEY_PLAYING = "playing"
        private const val KEY_HAS_PREVIOUS = "has_previous"
        private const val KEY_HAS_NEXT = "has_next"
        private const val KEY_LIKED = "liked"
        private const val KEY_CAN_LIKE = "can_like"
        private const val KEY_SHUFFLE = "shuffle"
    }
}
