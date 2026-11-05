package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactNavigationTabTest {
    @Test fun mainDestinationsKeepTheirSelectedIconAndLabel() {
        listOf(
            Tab("Home", NuvxIcons.Home),
            Tab("Explore", NuvxIcons.Explore),
            Tab("Library", NuvxIcons.Library),
        ).forEachIndexed { index, expected ->
            assertEquals(expected, compactNavigationTab(index, searching = false, page = null, albumTitle = null, artistTitle = null))
        }
    }

    @Test fun searchAndSubpagesUseTheirOwnDestinationIconAndLabel() {
        assertEquals(Tab("Search", NuvxIcons.Search), compactNavigationTab(0, searching = true, page = null, albumTitle = null, artistTitle = null))
        listOf(
            Page.TOGETHER to Tab("Listen Together", NuvxIcons.Together),
            Page.SETTINGS to Tab("Settings", NuvxIcons.Settings),
            Page.QUEUE to Tab("Queue", NuvxIcons.Queue),
            Page.HISTORY to Tab("History", NuvxIcons.History),
            Page.LIKED to Tab("Liked songs", NuvxIcons.HeartFilled),
            Page.LOCAL_LIBRARY to Tab("On this device", NuvxIcons.Library),
        ).forEach { (page, expected) ->
            assertEquals(expected, compactNavigationTab(0, searching = false, page = page, albumTitle = null, artistTitle = null))
        }
    }

    @Test fun albumAndArtistDetailsKeepLibrarySelected() {
        assertEquals(Tab("Album", NuvxIcons.Library), compactNavigationTab(0, false, null, albumTitle = "Album", artistTitle = null))
        assertEquals(Tab("Artist", NuvxIcons.Library), compactNavigationTab(0, false, null, albumTitle = null, artistTitle = "Artist"))
    }
}
