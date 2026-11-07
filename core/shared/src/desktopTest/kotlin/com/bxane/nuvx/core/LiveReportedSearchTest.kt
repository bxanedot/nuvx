package com.bxane.nuvx.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in live reproduction for the reported Android search term. */
class LiveReportedSearchTest {
    @Test fun travisBarkerRemixReturnsTracks() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact YouTube Music", System.getenv("NUVX_LIVE") == "1")
        val tracks = InnerTubeXBackend().search("Travis Barker remix").tracks
        println("tracks=${tracks.size} first=${tracks.take(5).map { it.title + " / " + it.artistLine }}")
        assertTrue(tracks.isNotEmpty(), "search returned no tracks")
    }
}
