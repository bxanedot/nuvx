package com.bxane.nuvx.data.radio

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioBrowserTest {
    @Test
    fun flagIsBuiltFromTheCountryCode() {
        assertEquals("🇩🇪", RadioCountry("Germany", "DE").flag)
        assertEquals("🇯🇵", RadioCountry("Japan", "jp").flag)
        assertEquals("", RadioCountry("Nowhere", "X1").flag)
    }

    @Test
    fun subtitleShowsTwoTagsThenFormat() {
        val station = RadioStation(id = "1", name = "x", streamUrl = "https://x", tags = "pop,rock,jazz", codec = "MP3", bitrate = 128)
        assertEquals("Pop · Rock · MP3 128 kbps", station.subtitle)
        assertEquals("AAC", station.copy(tags = "", codec = "AAC", bitrate = 0).subtitle)
    }
}
