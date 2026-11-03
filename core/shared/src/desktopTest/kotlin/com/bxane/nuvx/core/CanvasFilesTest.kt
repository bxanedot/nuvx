package com.bxane.nuvx.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasFilesTest {
    private val source = Files.createTempDirectory("canvas-src").toFile()
    private val cache = Files.createTempDirectory("canvas-cache").toFile()
    private fun remote(name: String, text: String) = File(source, name).apply { writeText(text) }.toURI().toString()

    @Test fun downloadsOnceThenReusesUntilItIsOld() {
        val url = remote("abcdefghijk.mp4", "first")
        val file = canvasFile(url, cache, now = 1_000)
        assertEquals("first", file.readText())
        File(source, "abcdefghijk.mp4").writeText("second")
        assertEquals("first", canvasFile(url, cache, now = 2_000).readText(), "a fresh copy is reused, not downloaded again")
        assertEquals("second", canvasFile(url, cache, now = 2_000 + 25L * 60 * 60 * 1000).readText(), "an old copy is refreshed")
    }

    @Test fun keepsOnlyTheMostRecentFilesAndRefusesHugeOnes() {
        (1..4).forEach { canvasFile(remote("id$it.mp4", "v$it"), cache, keep = 2, now = it * 1_000L) }
        assertEquals(setOf("id3.mp4", "id4.mp4"), cache.list()!!.toSet())
        assertFailsWith<IOException> { canvasFile(remote("big.mp4", "x".repeat(100)), cache, maxBytes = 10) }
        assertFalse(File(cache, "big.mp4").exists())
        assertTrue(cache.list()!!.none { it.endsWith(".part") })
    }
}
