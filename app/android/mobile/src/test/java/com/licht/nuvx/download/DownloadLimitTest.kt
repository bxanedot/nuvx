package com.licht.nuvx.download

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLimitTest {
    @Test
    fun offMeansNeverAndTheLimitIsInclusive() {
        assertFalse(Downloads.limitReached(Long.MAX_VALUE, 0))
        assertFalse(Downloads.limitReached(1_999_999_999L, 2))
        assertTrue(Downloads.limitReached(2_000_000_000L, 2))
    }

    @Test
    fun anOfflineStreamFolderCountsWhole() {
        val root = Files.createTempDirectory("dl").toFile()
        try {
            val single = File(root, "song.m4a").apply { writeBytes(ByteArray(300)) }
            val hls = File(root, "offline-hls/abc").apply { mkdirs() }
            File(hls, "playlist.m3u8").writeBytes(ByteArray(10))
            File(hls, "seg1.ts").writeBytes(ByteArray(1000))
            File(hls, "seg2.ts").writeBytes(ByteArray(1000))
            assertEquals(300L, DownloadStore.fileBytes(single))
            assertEquals(2010L, DownloadStore.fileBytes(File(hls, "playlist.m3u8")))
        } finally {
            root.deleteRecursively()
        }
    }
}
