package com.bxane.nuvx.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull








class RemoteArtworkTest {



    @Test
    fun `a cover named as one wins over the rest`() {


        assertEquals("https://dav.example.com/a/cover.jpg", RemoteArtwork.pick(listOf(
            "https://dav.example.com/a/IMG_1234.jpg",
            "https://dav.example.com/a/cover.jpg",
            "https://dav.example.com/a/back.jpg",
        )))
    }

    @Test
    fun `nothing that looks like a cover is no cover`() {



        assertEquals("https://dav.example.com/a/a.jpg", RemoteArtwork.pick(listOf(
            "https://dav.example.com/a/z.jpg",
            "https://dav.example.com/a/a.jpg",
        )))
    }

    @Test
    fun `an empty folder has no cover`() {
        assertNull(RemoteArtwork.pick(emptyList()))
    }

    @Test
    fun `every other conventional name is preferred to a stray one`() {
        val names = listOf("cover", "folder", "front", "albumart", "album", "artwork")
        names.forEach { name ->
            assertEquals(
                "https://dav.example.com/a/$name.jpg",
                RemoteArtwork.pick(listOf("https://dav.example.com/a/IMG_1234.jpg", "https://dav.example.com/a/$name.jpg")),
                "cover lost to IMG_1234 for a file called $name.jpg",
            )
        }
    }

    @Test
    fun `a name that starts with a conventional one still counts`() {
        assertEquals("https://dav.example.com/a/cover-front.png", RemoteArtwork.pick(listOf(
            "https://dav.example.com/a/scan.png",
            "https://dav.example.com/a/cover-front.png",
        )))
    }

    @Test
    fun `a word that merely contains one does not count`() {



        assertEquals("https://dav.example.com/a/album.jpg", RemoteArtwork.pick(listOf(
            "https://dav.example.com/a/the cover.jpg",
            "https://dav.example.com/a/album.jpg",
        )))
    }

    @Test
    fun `the order the server listed the folder in does not matter`() {



        val folder = listOf(
            "https://dav.example.com/a/z.jpg",
            "https://dav.example.com/a/cover.jpg",
            "https://dav.example.com/a/IMG_1234.jpg",
        )
        val orderings = listOf(
            folder,
            folder.reversed(),
            listOf(folder[1], folder[2], folder[0]),
            listOf(folder[2], folder[0], folder[1]),
        )
        val answers = orderings.map { RemoteArtwork.pick(it) }.toSet()
        assertEquals(setOf("https://dav.example.com/a/cover.jpg"), answers)
        assertEquals(setOf("https://dav.example.com/a/cover.jpg"), answers)
    }

    @Test
    fun `a name that is only different in case is still a name`() {

        assertEquals("https://dav.example.com/a/Cover.JPG", RemoteArtwork.pick(listOf(
            "https://dav.example.com/a/zzz.jpg",
            "https://dav.example.com/a/Cover.JPG",
        )))
    }



    @Test
    fun `a stem is the filename without its extension`() {
        assertEquals("cover", RemoteArtwork.stem("https://dav.example.com/a/cover.jpg"))
        assertEquals("album art", RemoteArtwork.stem("https://dav.example.com/a/album%20art.png"))
    }

    @Test
    fun `a query or a fragment is not part of a name`() {


        assertEquals("cover", RemoteArtwork.stem("https://dav.example.com/a/cover.jpg?X-Amz-Signature=a/b"))
        assertEquals("cover", RemoteArtwork.stem("https://dav.example.com/a/cover.jpg#frag"))
    }

    @Test
    fun `a plus is a plus and not a space`() {



        assertEquals("ac+dc - back", RemoteArtwork.stem("https://dav.example.com/a/AC%2BDC%20-%20Back.flac"))
        assertEquals("ac+dc - back", RemoteArtwork.stem("https://dav.example.com/a/AC+DC%20-%20Back.flac"))
    }

    @Test
    fun `a malformed escape is left as it is`() {


        assertEquals("100% pure", RemoteArtwork.stem("https://dav.example.com/a/100%25%20pure.jpg"))
        assertEquals("50%off", RemoteArtwork.stem("https://dav.example.com/a/50%off.jpg"))
    }

    @Test
    fun `a name with several dots keeps all of it but the last`() {
        assertEquals("cover.small", RemoteArtwork.stem("https://dav.example.com/a/cover.small.jpg"))
    }
}
