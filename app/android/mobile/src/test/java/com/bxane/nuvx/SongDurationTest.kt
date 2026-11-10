package com.bxane.nuvx

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.durationMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class SongDurationTest {

    private fun song(durationText: String?) = Song(
        videoId = "id",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        durationText = durationText,
    )

    @Test
    fun `minutes and seconds`() {
        assertEquals(225_000L, song("3:45").durationMillis())
    }

    @Test
    fun `a single-digit minute field, which is how most rows arrive`() {
        assertEquals(62_000L, song("1:02").durationMillis())
    }

    @Test
    fun `hours, minutes and seconds`() {
        assertEquals(3_753_000L, song("1:02:33").durationMillis())
    }

    @Test
    fun `surrounding and interior whitespace is tolerated`() {
        assertEquals(225_000L, song(" 3 : 45 ").durationMillis())
    }

    @Test
    fun `a row with no duration is zero rather than null`() {
        assertEquals(0L, song(null).durationMillis())
    }

    @Test
    fun `anything that isn't a duration is zero`() {
        assertEquals(0L, song("").durationMillis())
        assertEquals(0L, song("LIVE").durationMillis())
        assertEquals(0L, song("3:45:xx").durationMillis())
        assertEquals(0L, song("225").durationMillis())
        assertEquals(0L, song("1:2:3:4").durationMillis())
    }

    @Test
    fun `a negative field cannot produce a negative duration`() {
        assertEquals(0L, song("-3:45").durationMillis())
    }
}
