package com.bxane.nuvx.ui

import com.bxane.nuvx.core.MediaKind
import com.bxane.nuvx.core.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtistVideoBrowserTest {
    private val zulu = Track("aaaaaaaaaaa", "Zulu session", listOf("Primary artist"), durationMs = 90_000, mediaKind = MediaKind.VIDEO)
    private val alpha = Track("bbbbbbbbbbb", "Alpha concert", listOf("Guest artist"), durationMs = 30_000, mediaKind = MediaKind.VIDEO)
    private val unknown = Track("ccccccccccc", "Unknown length", listOf("Primary artist"), mediaKind = MediaKind.VIDEO)

    @Test fun blankSearchPreservesCatalogOrderAndMetadata() {
        val source = listOf(zulu, alpha, unknown)
        assertEquals(source, artistVideoItems(source, "  ", ArtistVideoOrder.CATALOG))
        assertEquals(listOf(zulu, alpha, unknown), source)
    }

    @Test fun searchMatchesTitlesAndCreditedArtistsIgnoringCaseAndOuterSpaces() {
        val source = listOf(zulu, alpha, unknown)
        assertEquals(listOf(alpha), artistVideoItems(source, " GUEST ", ArtistVideoOrder.CATALOG))
        assertEquals(listOf(zulu), artistVideoItems(source, "Zulu", ArtistVideoOrder.CATALOG))
        assertTrue(artistVideoItems(source, "not present", ArtistVideoOrder.CATALOG).isEmpty())
    }

    @Test fun titleSortingIsStableForCaseInsensitiveTies() {
        val tied = zulu.copy(videoId = "ddddddddddd", title = "ALPHA CONCERT")
        assertEquals(listOf(alpha, tied, zulu), artistVideoItems(listOf(zulu, alpha, tied), "", ArtistVideoOrder.TITLE))
    }

    @Test fun durationSortingKeepsUnknownAndNonpositiveLengthsLast() {
        val zero = unknown.copy(videoId = "ddddddddddd", durationMs = 0)
        val negative = unknown.copy(videoId = "eeeeeeeeeee", durationMs = -1)
        assertEquals(listOf(alpha, zulu, unknown, zero, negative),
            artistVideoItems(listOf(unknown, zulu, zero, alpha, negative), "", ArtistVideoOrder.DURATION))
    }
}
