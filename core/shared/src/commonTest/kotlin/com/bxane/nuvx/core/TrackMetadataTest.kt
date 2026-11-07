package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackMetadataTest {
    @Test fun metadataRoundTripKeepsArtistsAlbumDurationAndLocalUri() {
        val original = Track("local:123", "Silver Comet", listOf("Artist One", "Artist Two"), "Night Drive", 123_456, localUri = "content://media/audio/123", artistId = "UC1", albumId = "MPRE1")

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

    @Test fun pairedVideoModeSurvivesMediaSessionMetadata() {
        val paired = Track("song-id", "Silver Comet", listOf("Artist One"), durationMs = 240_000,
            mediaKind = MediaKind.VIDEO, pairedVideoId = "abcdefghijk")

        val restored = trackFromMetadata(paired.videoId, paired.title, paired.artistLine, paired.album, paired.artworkUrl, paired.toMetadataExtras())

        assertEquals(paired, restored)
        assertEquals("abcdefghijk", restored.playbackVideoId)
        assertEquals("song-id", restored.copy(mediaKind = MediaKind.SONG).playbackVideoId)
    }
}
