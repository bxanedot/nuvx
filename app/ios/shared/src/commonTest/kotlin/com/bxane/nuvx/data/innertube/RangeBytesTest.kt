package com.bxane.nuvx.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue













class RangeBytesTest {

    private fun url(client: String, extra: String = ""): String =
        "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&c=$client&itag=140$extra"

    @Test
    fun `a full-width client serves a megabyte`() {
        assertEquals(
            PlayerClient.RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("ANDROID_MUSIC")),
        )
        assertEquals(
            PlayerClient.RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("WEB_REMIX")),
        )
        assertEquals(
            PlayerClient.RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("IOS")),
        )
    }

    @Test
    fun `a narrow client serves half a megabyte and asking for more is a refusal`() {



        assertEquals(
            PlayerClient.NARROW_RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("ANDROID_VR")),
        )
        assertEquals(
            PlayerClient.NARROW_RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("TVHTML5_SIMPLY")),
        )
        assertTrue(PlayerClient.RANGE_BYTES > PlayerClient.NARROW_RANGE_BYTES)
    }

    @Test
    fun `the narrow client's own sibling is not narrow`() {


        assertEquals(
            PlayerClient.RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("TVHTML5")),
        )
    }

    @Test
    fun `the client's case does not matter`() {
        assertEquals(
            PlayerClient.NARROW_RANGE_BYTES,
            PlayerClient.rangeBytesFor(url("android_vr")),
        )
    }

    @Test
    fun `a URL naming no client is asked the full range`() {
        assertEquals(
            PlayerClient.RANGE_BYTES,
            PlayerClient.rangeBytesFor("https://rr3---sn-abc.googlevideo.com/videoplayback?itag=140"),
        )
    }

    @Test
    fun `a host that is not googlevideo's cap is nobody else's business`() {



        assertEquals(
            Long.MAX_VALUE,
            PlayerClient.rangeBytesFor("https://cdn.example.invalid/audio/song.flac"),
        )
    }

    @Test
    fun `the total length comes off the URL for free`() {
        assertEquals(
            4_194_304L,
            PlayerClient.lengthFromUrl("https://x.googlevideo.com/v?clen=4194304&c=IOS"),
        )
    }

    @Test
    fun `a URL with no length says so rather than guessing zero`() {


        assertNull(PlayerClient.lengthFromUrl("https://x.googlevideo.com/v?c=IOS"))
        assertNull(PlayerClient.lengthFromUrl("https://x.googlevideo.com/v?clen=abc&c=IOS"))
        assertNull(PlayerClient.lengthFromUrl("https://x.googlevideo.com/v"))
    }

    @Test
    fun `length is read past the other parameters`() {
        assertEquals(
            12_345L,
            PlayerClient.lengthFromUrl(
                "https://x.googlevideo.com/v?expire=1&ratebypass=yes&clen=12345&c=ANDROID_MUSIC"
            ),
        )
    }
}
