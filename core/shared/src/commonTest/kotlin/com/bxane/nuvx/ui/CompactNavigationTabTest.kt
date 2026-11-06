package com.bxane.nuvx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactNavigationTabTest {
    @Test fun mainDestinationsKeepTheirSelectedIconAndLabel() {
        listOf(
            Tab("Home", NuvxIcons.Home, "house.fill"),
            Tab("Explore", NuvxIcons.Explore, "square.grid.2x2.fill"),
            Tab("Library", NuvxIcons.Library, "music.note.square.stack.fill"),
        ).forEachIndexed { index, expected ->
            assertEquals(expected, compactNavigationTab(index, searching = false, page = null, albumTitle = null, artistTitle = null))
        }
    }

    @Test fun searchAndSubpagesUseTheirOwnDestinationIconAndLabel() {
        assertEquals(Tab("Search", NuvxIcons.Search, "magnifyingglass"), compactNavigationTab(0, searching = true, page = null, albumTitle = null, artistTitle = null))
        listOf(
            Page.TOGETHER to Tab("Listen Together", NuvxIcons.Together, "person.2.fill"),
            Page.SETTINGS to Tab("Settings", NuvxIcons.Settings, "gearshape.fill"),
            Page.QUEUE to Tab("Queue", NuvxIcons.Queue, "list.bullet"),
            Page.HISTORY to Tab("History", NuvxIcons.History, "clock.arrow.circlepath"),
            Page.LIKED to Tab("Liked songs", NuvxIcons.HeartFilled, "heart.fill"),
            Page.LOCAL_LIBRARY to Tab("On this device", NuvxIcons.Library, "iphone"),
        ).forEach { (page, expected) ->
            assertEquals(expected, compactNavigationTab(0, searching = false, page = page, albumTitle = null, artistTitle = null))
        }
    }

    @Test fun albumAndArtistDetailsKeepLibrarySelected() {
        assertEquals(Tab("Album", NuvxIcons.Library, "square.stack"), compactNavigationTab(0, false, null, albumTitle = "Album", artistTitle = null))
        assertEquals(Tab("Artist", NuvxIcons.Library, "square.stack"), compactNavigationTab(0, false, null, albumTitle = null, artistTitle = "Artist"))
    }
}
