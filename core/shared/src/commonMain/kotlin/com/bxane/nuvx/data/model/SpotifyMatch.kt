package com.bxane.nuvx.data.model

const val SPOTIFY_PENDING_PREFIX = "sp:"
const val SPOTIFY_MISSING_PREFIX = "sp-miss:"

val Song.isMatchPending: Boolean get() = videoId.startsWith(SPOTIFY_PENDING_PREFIX)

val Song.isMatchMissing: Boolean get() = videoId.startsWith(SPOTIFY_MISSING_PREFIX)

val Song.isUnresolvedSpotify: Boolean get() = isMatchPending || isMatchMissing
