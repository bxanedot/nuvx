package com.bxane.nuvx.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in: NUVX_LIVE=1. Checks that YouTube Music honours the filter tokens through the default engine. */
class LiveSearchFilterTest {
    @Test fun songsAndVideosFilters() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact YouTube Music", System.getenv("NUVX_LIVE") == "1")
        val backend = InnerTubeXBackend()
        val songs = backend.search("coldplay yellow", SearchFilter.SONGS).tracks
        val videos = backend.search("coldplay yellow", SearchFilter.VIDEOS).tracks
        println("songs=${songs.size} first=${songs.take(3).map { it.title + " / " + it.artistLine }}")
        println("videos=${videos.size} first=${videos.take(3).map { it.title + " / " + it.artistLine }}")
        assertTrue(songs.size >= 5 && videos.size >= 5, "both filters return results")
        assertTrue(songs.map { it.videoId }.toSet() != videos.map { it.videoId }.toSet(), "filters change the results")
    }
}
