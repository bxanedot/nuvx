package com.bxane.nuvx.desktop

import com.bxane.nuvx.core.Track
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class DesktopLocalPlaybackTest {
    @Test fun localTracksUseOnlyExistingFileUris() {
        val file = Files.createTempFile("nuvx-local-", ".mp3")
        try {
            val uri = file.toUri().toString()
            assertEquals(uri, Track("local:opaque", "Song", localUri = uri).localPlaybackUri())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun remoteTracksCannotSupplyLocalUris() {
        assertNull(Track("abcdefghijk", "Song").localPlaybackUri())
        assertFails { Track("abcdefghijk", "Song", localUri = "file:///secret.mp3").localPlaybackUri() }
    }

    @Test fun localTracksRejectMissingUnsupportedAndStaleUris() {
        assertFails { Track("local:opaque", "Song").localPlaybackUri() }
        assertFails { Track("local:opaque", "Song", localUri = "https://example.invalid/song.mp3").localPlaybackUri() }
        assertFails { Track("local:opaque", "Song", localUri = "file:///missing/nuvx-song.mp3").localPlaybackUri() }
    }
}
