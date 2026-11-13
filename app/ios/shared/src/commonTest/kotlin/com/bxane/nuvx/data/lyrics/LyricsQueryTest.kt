package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue









class LyricsQueryTest {

    @Test
    fun `a plain title is left alone`() {
        assertEquals("Dracula", "Dracula".forLyricsSearch())
    }

    @Test
    fun `bracketed credits go · because they are not part of the name` () {
        assertEquals("Dracula", "Dracula (feat. JENNIE)".forLyricsSearch())
        assertEquals("Anti-Hero", "Anti-Hero [ft. Taylor Swift]".forLyricsSearch())
        assertEquals("Sunset", "Sunset (with Caroline Polachek)".forLyricsSearch())
    }

    @Test
    fun `unbracketed credits go · and take the rest of the title with them` () {

        assertEquals("Ordinary", "Ordinary feat. Sam Fischer".forLyricsSearch())
    }

    @Test
    fun `upload packaging goes`() {
        assertEquals("Ordinary", "Ordinary (Official Video)".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary [Official Music Video]".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary (Lyrics)".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary (Audio)".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary (4K)".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary (HD)".forLyricsSearch())
        assertEquals("Ordinary", "Ordinary (Official)".forLyricsSearch())
    }

    @Test
    fun `artist-prefixed YouTube audio titles reduce to the catalog title`() {
        assertEquals(
            "FORTY",
            "AIKA & NAHREEL FORTY (AUDIO) FT AZAWI".forLyricsSearch("Aika & Nahreel, Azawi"),
        )
        assertEquals(
            "Forty",
            "Aika & Nahreel - Forty (Official Audio) ft. Azawi".forLyricsSearch("Aika & Nahreel, Azawi"),
        )

        assertEquals("Aika Song", "Aika Song".forLyricsSearch("Aika"))
    }

    @Test
    fun `featured artists in a YouTube title do not pollute Swalla lookup`() {
        assertEquals(
            "Swalla",
            "Swalla (feat. Nicki Minaj & Ty Dolla \$ign)".forLyricsSearch(
                "Jason Derulo, Nicki Minaj, Ty Dolla \$ign",
            ),
        )
    }



    @Test
    fun `a remix stays · because it names a different recording` () {
        assertEquals("Song (Remix)", "Song (Remix)".forLyricsSearch())
        assertEquals("Song (Acoustic)", "Song (Acoustic)".forLyricsSearch())
        assertEquals("Song (Live)", "Song (Live)".forLyricsSearch())
        assertEquals("Song (Sped Up)", "Song (Sped Up)".forLyricsSearch())
    }

    @Test
    fun `a remaster stays · and so does a year in the name` () {



        assertEquals("Song (Remastered 2011)", "Song (Remastered 2011)".forLyricsSearch())
    }

    @Test
    fun `credits and packaging go together without eating the recording`() {

        assertEquals(
            "Anti-Hero",
            "Anti-Hero (feat. Taylor Swift) (Official Music Video)".forLyricsSearch(),
        )
    }

    @Test
    fun `a remix with credits keeps the remix and loses the credits`() {
        assertEquals("Song (Live)", "Song (feat. X) (Live)".forLyricsSearch())
    }



    @Test
    fun `whitespace is collapsed and the ends trimmed`() {
        assertEquals("Song Title", "  Song   Title  ".forLyricsSearch())
        assertEquals("Song", "Song - ".forLyricsSearch())
        assertEquals("Song", "Song \u2014 ".forLyricsSearch())
    }

    @Test
    fun `a title that was only packaging falls back to what we were given`() {


        val packaging = "(Official Video)".forLyricsSearch()
        assertEquals("(Official Video)", packaging)
    }

    @Test
    fun `an empty title stays empty rather than becoming blank-with-noise`() {
        assertEquals("", "".forLyricsSearch())
    }



    @Test
    fun `an auto-generated channel name is trimmed`() {
        assertEquals("Tame Impala", "Tame Impala - Topic".artistForLyricsSearch())
    }

    @Test
    fun `a real artist with a Topic suffix in the middle is untouched`() {

        assertEquals("Topic", "Topic".artistForLyricsSearch())
        assertEquals("Two - Topic Bands", "Two - Topic Bands".artistForLyricsSearch())
    }

    @Test
    fun `an artist that was only the suffix is not lost`() {


        assertEquals("- Topic", " - Topic".artistForLyricsSearch())
    }



    @Test
    fun `a blank query is not worth a request`() {
        assertFalse("".isUsableLyricsQuery())
        assertFalse("   ".isUsableLyricsQuery())
    }

    @Test
    fun `a one-character title is a real thing and is asked about`() {

        assertTrue("X".isUsableLyricsQuery())
    }
}
