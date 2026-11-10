package com.bxane.nuvx.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchHistoryEntity(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val entityType: EntityType,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class EntityType {
    @SerialName("TRACK") TRACK,
    @SerialName("ALBUM") ALBUM,
    @SerialName("ARTIST") ARTIST,
    @SerialName("PLAYLIST") PLAYLIST,
    @SerialName("OTHER") OTHER,
}

fun SearchHistoryEntity.withCorrectedType(): SearchHistoryEntity =
    if (entityType == EntityType.TRACK && id.startsWith("MPSPP")) copy(entityType = EntityType.OTHER) else this

fun SearchHistoryEntity.toSong(): Song? = if (entityType == EntityType.TRACK && !id.startsWith("q:")) {
    Song(
        videoId = id,
        title = title,
        artist = subtitle,
        thumbnailUrl = artworkUrl,
    )
} else null
