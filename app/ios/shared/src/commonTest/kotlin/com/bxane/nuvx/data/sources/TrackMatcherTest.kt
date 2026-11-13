package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue










class TrackMatcherTest {

    private fun song(
        title: String,
        artist: String,
        duration: String? = null,
        album: String? = null,
        explicit: Boolean? = null,
        video: Boolean = false,
        id: String = "x",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        durationText = duration,
        albumName = album,
        isExplicit = explicit,
        isVideo = video,
    )



    @Test
    fun refusesADifferentRecordingOfTheSameTitle() {
        val target = TrackMatcher.Target("Yesterday", "The Beatles", 125)


        val cover = song("Yesterday", "David Cover", "4:41")
        assertNull(TrackMatcher.score(cover, target), "a different singer is a different recording")
    }















    @Test
    fun anExactRuntimeAdmitsACoverButOnlyAsALastResort() {
        val target = TrackMatcher.Target("Yesterday", "The Beatles", 125)
        val cover = song("Yesterday", "David Cover", "2:05")
        val coverScore = TrackMatcher.score(cover, target)
        assertNotNull(coverScore, "an exact runtime is the tie-breaker when the credits disagree")
        val creditedScore = TrackMatcher.score(song("Yesterday", "The Beatles", "2:05"), target)
        assertNotNull(creditedScore)
        assertTrue(
            creditedScore > coverScore,
            "a proper credit must always outrank the override: $creditedScore vs $coverScore",
        )
    }

    @Test
    fun refusesTheOtherDirectionOfAVersionMarker() {
        val albumCut = TrackMatcher.Target("Bohemian Rhapsody", "Queen", 355)
        assertNull(
            TrackMatcher.score(song("Bohemian Rhapsody (Live)", "Queen", "5:55"), albumCut),
            "asking for the album cut must not land on the live take",
        )
        val liveTake = TrackMatcher.Target("Bohemian Rhapsody (Live)", "Queen", 355)
        assertNull(
            TrackMatcher.score(song("Bohemian Rhapsody", "Queen", "5:55"), liveTake),
            "asking for the live take must not land on the album cut",
        )
    }

    @Test
    fun acceptsTheSameRecording() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", 300)
        assertNotNull(TrackMatcher.score(song("Paniyon Sa", "Atif Aslam", "5:00"), target))
    }






    @Test
    fun acceptsAPartialCredit() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", 300)
        assertNotNull(
            TrackMatcher.score(song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", "5:00"), target),
            "a duet with only one singer in the title is still the duet",
        )
    }

    @Test
    fun acceptsAnInitialledCredit() {
        val target = TrackMatcher.Target("Jai Ho", "A. R. Rahman", 320)
        assertNotNull(TrackMatcher.score(song("Jai Ho", "AR Rahman", "5:20"), target))
    }

    @Test
    fun matchesAcrossPackagingInTheTitle() {

        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", 300)
        assertNotNull(
            TrackMatcher.score(song("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam", "5:00"), target),
        )
    }

    @Test
    fun doesNotMatchQueenInsideQueensryche() {
        val target = TrackMatcher.Target("Bohemian Rhapsody", "Queen", 355)
        assertNull(
            TrackMatcher.score(song("Bohemian Rhapsody", "Queensrÿche", "6:12"), target),
            "a name inside a longer name is not a credit",
        )
    }



    @Test
    fun refusesADifferentCutOfTheSameSong() {












        val target = TrackMatcher.Target("Punjabi Dj Holi Songs", "Various", 180)
        assertNotNull(
            TrackMatcher.score(song("Punjabi Dj Holi Songs", "Various", "3:05"), target),
            "a five-second drift inside the limit is a loose match",
        )
        assertNotNull(
            TrackMatcher.score(song("Punjabi Dj Holi Songs", "Various", "4:12"), target),
            "a shared credit earns the wider window",
        )
        assertNull(
            TrackMatcher.score(song("Punjabi Dj Holi Songs", "Some Other Act", "4:12"), target),
            "a different artist does not, whatever the runtime",
        )
        assertNull(
            TrackMatcher.score(song("Punjabi Dj Holi Songs", "Various", "6:00"), target),
            "past the wider window it is a different recording",
        )
    }

    @Test
    fun aSevereRuntimeDifferenceIsNotTheSameRecording() {
        assertTrue(TrackMatcher.isSevereMismatch(180, 241))
        assertFalse(TrackMatcher.isSevereMismatch(180, 182))
        assertFalse(TrackMatcher.isSevereMismatch(null, 241), "an unknown length is not a mismatch")
    }

    @Test
    fun aVideoMayRunLongerThanItsAudio() {


        val target = TrackMatcher.Target("Brown Rang", "Yo Yo Honey Singh", 211, isVideo = true)
        assertNotNull(
            TrackMatcher.score(song("Brown Rang", "Yo Yo Honey Singh", "2:31"), target),
            "a video's visual outro is credible drift when the artist agrees",
        )
    }









    @Test
    fun anExactRuntimeStandsInForAMissingSharedCredit() {
        val target = TrackMatcher.Target("Jhak Maar Ke", "Pritam", 233)
        assertNotNull(TrackMatcher.score(song("Jhak Maar Ke", "Neeraj Shridhar", "3:53"), target))
    }

    @Test
    fun anApproximateRuntimeDoesNot() {
        val target = TrackMatcher.Target("Jhak Maar Ke", "Pritam", 233)
        assertNull(
            TrackMatcher.score(song("Jhak Maar Ke", "Neeraj Shridhar", "3:50"), target),
            "three seconds either side is not the corroboration this needs",
        )
    }



    @Test
    fun anExactReleaseBeatsACloserRuntime() {
        val target = TrackMatcher.Target("Hollow", "Twenty One Pilots", 300, album="Scaled and Icy")
        val wrongAlbum = song("Hollow", "Twenty One Pilots", "5:01", album="Blurryface")
        val rightAlbum = song("Hollow", "Twenty One Pilots", "5:00", album="Scaled and Icy")
        val ranked = TrackMatcher.ranked(listOf(wrongAlbum, rightAlbum), target)
        assertEquals(rightAlbum.videoId, ranked.first().videoId)
    }

    @Test
    fun anExplicitEditionMustAgreeWhenBothSaySo() {
        val target = TrackMatcher.Target("Wild Thoughts", "DJ Khaled", 200, isExplicit = true)
        assertNull(
            TrackMatcher.score(song("Wild Thoughts", "DJ Khaled", "3:20", explicit = false), target),
            "clean and uncensored are different masters",
        )
    }

    @Test
    fun anUnstatedEditionIsNotRejectedForHavingNoClaim() {
        val target = TrackMatcher.Target("Wild Thoughts", "DJ Khaled", 200, isExplicit = true)
        assertNotNull(
            TrackMatcher.score(song("Wild Thoughts", "DJ Khaled", "3:20", explicit = null), target),
        )
    }



    @Test
    fun theSearchQueryDropsThePackagingButKeepsTheVersion() {
        val queries = TrackMatcher.queries(TrackMatcher.Target("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam"))
        assertTrue(queries.isNotEmpty())
        assertTrue(
            queries.none { "Satyamev" in it },
            "handing a catalogue words it has never stored is asking it not to match: $queries",
        )
        assertEquals(2, queries.size, "title+artist, then title alone")
    }

    @Test
    fun aVersionMarkerSurvivesIntoTheQuery() {
        val queries = TrackMatcher.queries(TrackMatcher.Target("Song (Acoustic)", "Artist"))
        assertTrue(
            queries.any { "acoustic" in it },
            "dropping the version marker would let the plain take answer for it: $queries",
        )
    }

    @Test
    fun anAlbumVersionIsNotAVersionMarker() {







        val target = TrackMatcher.Target("Clocks", "Coldplay", 300)
        assertNotNull(TrackMatcher.score(song("Clocks (Album Version)", "Coldplay", "5:00"), target))
        assertNotNull(TrackMatcher.score(song("Clocks (Radio Edit)", "Coldplay", "5:00"), target))
    }



    @Test
    fun takesTheBestMatchNotTheFirst() {
        val target = TrackMatcher.Target("Hollow", "Twenty One Pilots", 300, album = "Scaled and Icy")
        val candidates = listOf(
            song("Hollow (Live)", "Twenty One Pilots", "6:11", id = "live"),
            song("Hollow", "Some Cover", "3:20", id = "cover"),
            song("Hollow", "Twenty One Pilots", "5:00", album = "Scaled and Icy", id = "right"),
        )
        assertEquals("right", TrackMatcher.best(candidates, target)?.videoId)
    }

    @Test
    fun aProperlyCreditedRowBeatsOneTheRuntimeVouchedFor() {
        val target = TrackMatcher.Target("Jhak Maar Ke", "Pritam", 233)
        val candidates = listOf(
            song("Jhak Maar Ke", "Neeraj Shridhar", "3:53", id = "runtime"),
            song("Jhak Maar Ke", "Pritam", "3:53", id = "credited"),
        )
        assertEquals("credited", TrackMatcher.best(candidates, target)?.videoId)
    }



    @Test
    fun findsTheCatalogueAudioForAVideoRow() {
        val target = TrackMatcher.Target("Brown Rang", "Yo Yo Honey Singh", 211, isVideo = true)
        val found = TrackMatcher.bestOfficialAudioForVideo(
            listOf(
                song("Brown Rang", "Different Artist", "3:31", id = "cover"),
                song("Brown Rang", "Yo Yo Honey Singh", "3:31", id = "audio"),
            ),
            target,
        )
        assertEquals("audio", found?.videoId)
    }

    @Test
    fun officialVideoRankingAcceptsLongAndShortCutsWithoutLettingDurationVeto() {
        val target = TrackMatcher.Target("Thriller", "Michael Jackson", 5 * 60 + 57, isVideo = true)
        val ranked = TrackMatcher.rankedOfficialVideos(
            listOf(
                song("Thriller", "Michael Jackson", "13:42", id = "long-cut"),
                song("Thriller", "Michael Jackson", "3:22", id = "short-cut"),
            ),
            target,
        )

        assertEquals(listOf("short-cut", "long-cut"), ranked.map { it.videoId })
    }

    @Test
    fun officialVideoRankingRejectsSameTitleByDifferentArtist() {
        val target = TrackMatcher.Target("Down", "Shayne Ward", 205, isVideo = true)
        val jaySean = song("Down (feat. Lil Wayne)", "Jay Sean", "4:00", id = "jay-sean")

        assertTrue(TrackMatcher.rankedOfficialVideos(listOf(jaySean), target).isEmpty())
    }

    @Test
    fun officialVideoRankingRejectsLiveVersionForStudioTarget() {
        val target = TrackMatcher.Target("Bohemian Rhapsody", "Queen", 355, isVideo = true)
        val wembley = song("Bohemian Rhapsody (Live At Wembley Stadium)", "Queen", "5:57", id = "wembley")

        assertTrue(TrackMatcher.rankedOfficialVideos(listOf(wembley), target).isEmpty())
    }



    @Test
    fun flagsConflictingReleasesWhenTheTargetNamesNone() {
        val target = TrackMatcher.Target("Song", "Artist", 200)
        val candidates = listOf(
            song("Song", "Artist", "3:20", album = "Original 2011", id = "a"),
            song("Song", "Artist", "3:21", album = "Compilation", id = "b"),
        )
        assertTrue(TrackMatcher.hasConflictingAlbums(candidates, target))
    }

    @Test
    fun doesNotFlagWhenTheTargetNamesTheRelease() {
        val target = TrackMatcher.Target("Song", "Artist", 200, album = "Original 2011")
        val candidates = listOf(
            song("Song", "Artist", "3:20", album = "Original 2011", id = "a"),
            song("Song", "Artist", "3:21", album = "Compilation", id = "b"),
        )
        assertFalse(TrackMatcher.hasConflictingAlbums(candidates, target))
    }

    @Test
    fun resolvesACollisionOnlyOnAUniquelyFullerCredit() {
        val target = TrackMatcher.Target("Jhak Maar Ke", "Pritam, Neeraj Shridhar, Arijit Singh", 233)
        val fuller = song("Jhak Maar Ke", "Neeraj Shridhar, Arijit Singh, Pritam", "3:53", id = "fuller")
        val abridged = song("Jhak Maar Ke", "Neeraj Shridhar", "3:53", id = "abridged")
        assertEquals(
            "fuller",
            TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(abridged, fuller), target)?.videoId,
        )
    }

    @Test
    fun refusesACollisionOnATie() {
        val target = TrackMatcher.Target("Song", "Artist", 200)
        val candidates = listOf(
            song("Song", "Artist One", "3:20", album = "A", id = "a"),
            song("Song", "Artist Two", "3:21", album = "B", id = "b"),
        )
        assertNull(
            TrackMatcher.uniquelyMostCreditedCloseMatch(candidates, target),
            "a tie is not evidence, and guessing is what plays the wrong song",
        )
    }



    @Test
    fun parsesBothDurationShapes() {
        assertEquals(225, TrackMatcher.secondsOf("3:45"))
        assertEquals(3723, TrackMatcher.secondsOf("1:02:03"))
        assertNull(TrackMatcher.secondsOf("3"))
        assertNull(TrackMatcher.secondsOf("0:00"))
        assertNull(TrackMatcher.secondsOf("not a time"))
    }
}
