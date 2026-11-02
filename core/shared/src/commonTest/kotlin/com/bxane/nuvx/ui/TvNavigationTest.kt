package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TvNavigationTest {
    @Test fun backUnwindsPlayerThenPageThenSearchThenHome() {
        val player = TvNavigationState(
            destination = TvDestination.NOW_PLAYING,
            pageReturn = TvDestination.SEARCH,
            playerReturn = TvDestination.SETTINGS,
            subpage = TvSubpage.SETTINGS,
        )
        val settings = player.back()
        assertEquals(TvDestination.SETTINGS, settings.destination)
        assertEquals(TvSubpage.SETTINGS, settings.subpage)

        val search = settings.back()
        assertEquals(TvDestination.SEARCH, search.destination)
        assertEquals(null, search.subpage)
        assertEquals(TvDestination.HOME, search.back().destination)
    }

    @Test fun backClosesAlbumBeforeLeavingSearch() {
        val searchAlbum = TvNavigationState(TvDestination.SEARCH, albumOpen = true)

        val search = searchAlbum.back()
        assertEquals(false, search.albumOpen)
        assertEquals(TvDestination.SEARCH, search.destination)
        assertEquals(TvDestination.HOME, search.back().destination)
    }

    @Test fun backReturnsFromLocalLibraryToLibraryTab() {
        val localLibrary = TvNavigationState(
            destination = TvDestination.LIBRARY,
            pageReturn = TvDestination.LIBRARY,
            subpage = TvSubpage.LOCAL_LIBRARY,
        )

        val library = localLibrary.back()
        assertEquals(TvDestination.LIBRARY, library.destination)
        assertEquals(null, library.subpage)
    }
}
