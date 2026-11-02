package com.bxane.nuvx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCachePolicyTest {
    @Test fun localContentAndFileStreamsBypassCache() {
        assertTrue(isLocalPlaybackScheme("content"))
        assertTrue(isLocalPlaybackScheme("file"))
        assertFalse(shouldCachePlaybackScheme("content"))
        assertFalse(shouldCachePlaybackScheme("file"))
    }

    @Test fun resolvedNetworkStreamsUseCache() {
        assertTrue(shouldCachePlaybackScheme("https"))
        assertTrue(shouldCachePlaybackScheme("http"))
        assertFalse(isLocalPlaybackScheme("https"))
    }
}
