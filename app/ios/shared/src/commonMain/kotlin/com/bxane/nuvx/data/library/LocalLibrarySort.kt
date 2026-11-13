package com.bxane.nuvx.data.library

import kotlinx.serialization.Serializable









enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
    ;

    val label: String
        get() = when (this) {
            TITLE_ASC -> "Title"
            TITLE_DESC -> "Title (Reverse)"
            DATE_ADDED -> "Date Added"
            DATE_MODIFIED -> "Date Modified"
        }
}








enum class LocalViewType {
    LIST,
    GRID,
}














@Serializable
data class LocalSong(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Double = 0.0,
    val dateAddedSeconds: Long? = null,
    val dateModifiedSeconds: Long? = null,
)













fun List<LocalSong>.sortedForLibrary(order: LocalMusicSort): List<LocalSong> = when (order) {
    LocalMusicSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    LocalMusicSort.TITLE_DESC -> sortedWith(
        compareByDescending<LocalSong> { it.title.lowercase() },
    )
    LocalMusicSort.DATE_ADDED -> sortedWith(
        compareByDescending<LocalSong> { it.dateAddedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    LocalMusicSort.DATE_MODIFIED -> sortedWith(
        compareByDescending<LocalSong> { it.dateModifiedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}












fun LocalSong.matchesSearch(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return title.contains(needle, ignoreCase = true) ||
        artist.contains(needle, ignoreCase = true) ||
        album.contains(needle, ignoreCase = true)
}








fun List<LocalSong>.libraryView(order: LocalMusicSort, query: String): List<LocalSong> =
    filter { it.matchesSearch(query) }.sortedForLibrary(order)
