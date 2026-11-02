package com.bxane.nuvx.core

import kotlinx.coroutines.flow.StateFlow

data class LocalMusicState(
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val actionLabel: String,
    val error: String? = null,
)

/** Platform adapters request Android audio permission or open a desktop folder picker only after the local screen opens. */
interface LocalMusicLibrary {
    val state: StateFlow<LocalMusicState>
    fun open()
    fun requestAccess()
}
