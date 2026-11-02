package com.bxane.nuvx.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLocalMusicTest {
    @Test fun scansSupportedFilesRecursivelyAndSkipsSymbolicLinks() {
        val root = Files.createTempDirectory("nuvx-music-")
        val nested = Files.createDirectories(root.resolve("nested"))
        val alpha = Files.write(nested.resolve("Alpha.FLAC"), byteArrayOf(1))
        Files.write(root.resolve("zeta.mp3"), byteArrayOf(1))
        Files.write(root.resolve("ignore.txt"), byteArrayOf(1))
        Files.createDirectory(root.resolve("folder.mp3"))
        val link = root.resolve("linked.mp3")
        val linkCreated = runCatching { Files.createSymbolicLink(link, alpha) }.isSuccess

        try {
            val tracks = scanLocalMusic(root.toFile())
            assertEquals(listOf("Alpha", "zeta"), tracks.map { it.title })
            assertEquals("file", tracks.first().localUri?.substringBefore(':'))
            if (linkCreated) assertEquals(2, tracks.size)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(root.resolve("folder.mp3"))
            Files.deleteIfExists(root.resolve("ignore.txt"))
            Files.deleteIfExists(root.resolve("zeta.mp3"))
            Files.deleteIfExists(alpha)
            Files.deleteIfExists(nested)
            Files.deleteIfExists(root)
        }
    }
}
