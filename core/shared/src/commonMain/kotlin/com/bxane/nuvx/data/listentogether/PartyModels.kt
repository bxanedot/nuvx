package com.bxane.nuvx.data.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartyTrack(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val fromAutoplay: Boolean = false,
)

@Serializable
data class PartyMember(
    val memberId: String,
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    val connected: Boolean = false,
    val joinedAtMs: Long = 0,
    val lastSeenMs: Long = 0,
)

@Serializable
data class PartyPlayback(
    val seq: Long = 0,
    val track: PartyTrack? = null,
    val queueSeq: Long = 0,
    val queueLength: Int = 0,
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorMs: Long = 0,
    val effectivePositionMs: Long = 0,
    val updatedBy: String? = null,
    val startedBy: String? = null,
    val startedByName: String? = null,
    val autoplayEnabled: Boolean = false,
    val updatedAtMs: Long = 0,
)

@Serializable
data class PartyQueue(
    val seq: Long = 0,
    val index: Int = -1,
    val items: List<PartyTrack> = emptyList(),
)

@Serializable
data class PartySnapshot(
    val code: String = "",
    val createdAtMs: Long = 0,
    val maxMembers: Int = 5,
    val hostOnlyControl: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val serverMs: Long = 0,
)

@Serializable
data class PartyPreview(
    val code: String = "",
    val hostName: String = "",
    val memberCount: Int = 0,
    val maxMembers: Int = 5,
    val isFull: Boolean = false,
    val members: List<PartyPreviewMember> = emptyList(),
)

@Serializable
data class PartyPreviewMember(
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
)

@Serializable
data class PartyMembership(
    val code: String,
    val token: String,
    val you: PartyMember,
    val party: PartySnapshot,
    val serverMs: Long = 0,
)

@Serializable
data class PartyActivity(
    val action: String,
    val by: String,
    val atMs: Long,
    val detail: String = "",
)

@Serializable
data class JoinRequest(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val maxMembers: Int? = null,
    val autoplayEnabled: Boolean? = null,
)

@Serializable
data class ApiError(
    @SerialName("error") val code: String = "",
    val message: String = "",
)
