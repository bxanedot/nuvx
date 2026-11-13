package com.bxane.nuvx.data.lyrics







enum class LyricsSource(
    val label: String,
    val detail: String,
    val wordSynced: Boolean,
) {



















    LRC_RED(
        label = "lrc.red",
        detail = "Apple Music timings, filed by recording",
        wordSynced = true,
    ),
    BINI_LYRICS(
        label = "BiniLyrics",
        detail = "Apple Music timings, and the recording's ISRC for everything else",
        wordSynced = true,
    ),
    LYRICS_PLUS(
        label = "LyricsPlus",
        detail = "Syllable by syllable, on community mirrors",
        wordSynced = true,
    ),
    PAXSENIX(
        label = "PaxSenix",
        detail = "Apple Music timings again, on a second host",
        wordSynced = true,
    ),








    PAXSENIX_SPOTIFY(
        label = "PaxSeniX Spotify",
        detail = "Spotify's own lyrics, on a second catalogue · needs a key",
        wordSynced = true,
    ),







    PAXSENIX_MUSIXMATCH(
        label = "PaxSeniX Musixmatch",
        detail = "Musixmatch again, through a different host · needs a key",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        label = "BetterLyrics",
        detail = "Apple Music timings, word by word",
        wordSynced = true,
    ),







    BETTER_LYRICS_PORTATO(
        label = "BetterLyrics Portato",
        detail = "QQ Music karaoke timings, on a second endpoint",
        wordSynced = true,
    ),
    SIMP_MUSIC(
        label = "SimpMusic",
        detail = "Matched on the video, so never the wrong edit",
        wordSynced = true,
    ),
    KUGOU(
        label = "KuGou",
        detail = "Whole lines, strong outside the English catalogue",
        wordSynced = false,
    ),
    LRCLIB(
        label = "LRCLIB",
        detail = "Whole lines only, and always up",
        wordSynced = false,
    ),
    MUSIXMATCH(
        label = "Musixmatch",
        detail = "Whole lines, from the biggest lyrics database there is",
        wordSynced = false,
    ),





    UNISON(
        label = "Unison",
        detail = "Community submissions; can be word synced, line synced or plain",
        wordSynced = true,
    ),







    YOUTUBE_TRANSCRIPT(
        label = "YouTube Transcript",
        detail = "YouTube's own captions for this video, line by line",
        wordSynced = false,
    ),






    YOUTUBE_MUSIC(
        label = "YouTube Music",
        detail = "YouTube Music's own lyrics, with no timing",
        wordSynced = false,
    ),









    GENIUS(
        label = "Genius",
        detail = "Community database, scraped; no timing, and the first to break",
        wordSynced = false,
    ),





    MEGALOBIZ(
        label = "Megalobiz",
        detail = "Scraped community LRC; the long tail, and the first to break",
        wordSynced = false,
    ),
    ;

    companion object {
        private val LEGACY = mapOf(
            "BETTER" to BETTER_LYRICS,
            "PLUS" to LYRICS_PLUS,
            "SIMP" to SIMP_MUSIC,
        )

        fun fromName(raw: String): LyricsSource? {
            val name = raw.trim()
            if (name.isEmpty()) return null
            entries.firstOrNull { it.name == name }?.let { return it }
            return LEGACY[name]
        }
    }
}
