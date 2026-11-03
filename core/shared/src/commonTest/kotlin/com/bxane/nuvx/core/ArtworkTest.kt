package com.bxane.nuvx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkTest {
    @Test fun artworkIsUpsizedForGoogleAndYouTubeImages() {
        assertEquals("https://lh3.googleusercontent.com/x=w1080-h1080-l90-rj", hiResArtwork("https://lh3.googleusercontent.com/x=w120-h120-l90-rj"))
        assertEquals("https://yt3.ggpht.com/y=s1080-c-k", hiResArtwork("https://yt3.ggpht.com/y=s88-c-k"))
        assertEquals("https://i.ytimg.com/vi/a/maxresdefault.jpg", hiResArtwork("https://i.ytimg.com/vi/a/hqdefault.jpg?sqp=x&rs=y"))
        assertEquals("https://i.ytimg.com/vi/a/hqdefault.jpg", hiResArtwork("https://i.ytimg.com/vi/a/mqdefault.jpg", 300))
        assertEquals("https://example.com/a.png", hiResArtwork("https://example.com/a.png"))
        assertEquals(null, hiResArtwork(null))
    }
}
