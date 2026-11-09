package com.bxane.nuvx

import com.bxane.nuvx.ui.screens.downloadsCoverGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsCoverGridTest {
    private val covers = listOf("a", "b", "c", "d", "e", "a")

    @Test
    fun `four distinct covers, the same pick for the same seed`() {
        val grid = downloadsCoverGrid(covers, emptyList(), seed = 7)
        assertEquals(4, grid.toSet().size)
        assertTrue(covers.containsAll(grid))
        assertEquals(grid, downloadsCoverGrid(covers, emptyList(), seed = 7))
    }

    @Test
    fun `too few covers leaves the placeholder`() {
        assertEquals(emptyList<String>(), downloadsCoverGrid(listOf("a", "b", "c", "c"), emptyList(), seed = 1))
    }

    @Test
    fun `two artist photos join once several artists are downloaded`() {
        assertEquals(0, downloadsCoverGrid(covers, listOf("x"), seed = 3).count { it == "x" })
        assertEquals(2, downloadsCoverGrid(covers, listOf("x", "y", "z"), seed = 3).count { it in setOf("x", "y", "z") })
    }
}
