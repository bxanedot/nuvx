package com.bxane.nuvx.ui

internal enum class TvDestination { HOME, EXPLORE, LIBRARY, SEARCH, NOW_PLAYING, TOGETHER, SETTINGS }
internal enum class TvSubpage { TOGETHER, SETTINGS, QUEUE, LIKED, LOCAL_LIBRARY }

internal data class TvNavigationState(
    val destination: TvDestination = TvDestination.HOME,
    val pageReturn: TvDestination = TvDestination.HOME,
    val playerReturn: TvDestination = TvDestination.HOME,
    val subpage: TvSubpage? = null,
    val albumOpen: Boolean = false,
) {
    fun back() = when {
        destination == TvDestination.NOW_PLAYING -> copy(destination = playerReturn)
        albumOpen -> copy(albumOpen = false)
        subpage != null -> copy(destination = pageReturn, subpage = null)
        destination != TvDestination.HOME -> copy(destination = TvDestination.HOME)
        else -> this
    }
}
