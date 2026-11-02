package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackMetadataTest {
    @Test fun metadataRoundTripKeepsArtistsAlbumDurationAndLocalUri() {
        val original = Track("local:123", "Silver Comet", listOf("Artist One", "Artist Two"), "Night Drive", 123_456, localUri = "content://media/audio/123")

        assertEquals(
            original,
            trackFromMetadata(original.videoId, original.title, original.artistLine, original.album, original.artworkUrl, original.toMetadataExtras()),
        )
    }

    @Test fun legacyMetadataFallsBackToSingleArtistAndAlbum() {
        assertEquals(
            Track("abcdefghijk", "Silver Comet", listOf("Artist One"), "Night Drive"),
            trackFromMetadata("abcdefghijk", "Silver Comet", "Artist One", "Night Drive", null, emptyMap()),
        )
    }
}
