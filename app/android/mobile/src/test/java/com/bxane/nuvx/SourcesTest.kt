package com.bxane.nuvx

import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.jiosaavn.RawSongItem
import com.bxane.nuvx.data.jiosaavn.prioritizeExplicit
import com.bxane.nuvx.data.jiosaavn.selectBestSaavnStream
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.DeviceCodecs
import com.bxane.nuvx.data.sources.ModuleSource
import com.bxane.nuvx.data.sources.MusicSource
import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceHealth
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.StreamRequest
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class SourcesTest {

    private companion object {
        const val RACE_TITLE = "Paniyon Sa"
        const val RACE_ARTIST = "Atif Aslam"
    }

    @Test
    fun `track key round-trips`() {
        val key = SourceRegistry.trackKey("cfg-1", "track-42")
        assertEquals("cfg-1" to "track-42", SourceRegistry.parseTrackKey(key))
    }

    @Test
    fun `track key survives separators inside the track id`() {
        val key = SourceRegistry.trackKey("cfg-1", "al::bum::7")
        assertEquals("cfg-1" to "al::bum::7", SourceRegistry.parseTrackKey(key))
    }

    @Test
    fun `plain video ids are not source keys`() {
        assertNull(SourceRegistry.parseTrackKey("dQw4w9WgXcQ"))
        assertNull(SourceRegistry.parseTrackKey(""))
    }

    @Test
    fun `lossless is decided by codec, not bitrate`() {
        assertEquals(true, StreamFormat(codec = "flac", kbps = 900).isLossless)
        assertEquals(true, StreamFormat(codec = "alac").isLossless)
        assertEquals(false, StreamFormat(codec = "opus", sampleRateHz = 192_000).isLossless)
        assertNull(StreamFormat().isLossless)
    }

    @Test
    fun `summary states depth and rate and drops bitrate when lossless`() {
        val hiRes = StreamFormat(codec = "flac", kbps = 4608, sampleRateHz = 192_000, bitDepth = 24)
        assertEquals("FLAC · 24-bit · 192 kHz", hiRes.summary)

        val lossy = StreamFormat(codec = "mp3", kbps = 320, sampleRateHz = 44_100)
        assertEquals("MP3 · 44.1 kHz · 320 kbps", lossy.summary)

        assertEquals("Unknown format", StreamFormat().summary)
    }

    @Test
    fun `Dolby Atmos is a distinct premium codec`() {
        val atmos = StreamFormat(codec = "eac3-joc")
        assertTrue(atmos.isDolbyAtmos)
        assertEquals(false, atmos.isLossless)
        assertEquals("Dolby Atmos", atmos.summary)
        assertTrue(SourceResolver.worthSwapping(atmos, StreamFormat(codec = "aac", kbps = 332)))
    }

    @Test
    fun `an Atmos rendition is refused when it is not allowed to play`() {
        val atmos = StreamFormat(codec = "eac3-joc")
        assertTrue(ModuleSource.unplayable(atmos, atmosAllowed = false))
        assertFalse(ModuleSource.unplayable(atmos, atmosAllowed = true))

        val flac = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)
        assertFalse(ModuleSource.unplayable(flac, atmosAllowed = false))
        assertFalse(ModuleSource.unplayable(StreamFormat(), atmosAllowed = false))
    }

    @Test
    fun `names a high-bitrate lossy stream without calling it lossless`() {
        val aac320 = NerdStats.Snapshot(mimeType = "audio/mp4a-latm", bitrateKbps = 320, sampleRateHz = 44_100, channels = 2)
        assertFalse(aac320.isLossless)
        assertTrue(aac320.isHiQuality)

        val opus160 = NerdStats.Snapshot(mimeType = "audio/opus", bitrateKbps = 160, sampleRateHz = 48_000, channels = 2)
        assertFalse(opus160.isHiQuality)

        val flac = NerdStats.Snapshot(mimeType = "audio/flac", bitrateKbps = 1411, sampleRateHz = 44_100, channels = 2)
        assertTrue(flac.isLossless)
        assertFalse(flac.isHiQuality)
    }

    @Test
    fun `the declared format never earns a quality tier by itself`() {
        val claimedHiQuality = NerdStats.Snapshot(
            mimeType = "audio/mp4a-latm",
            bitrateKbps = null,
            sampleRateHz = 44_100,
            channels = 2,
            claimed = StreamFormat(codec = "aac", kbps = 320),
        )
        assertFalse(claimedHiQuality.isHiQuality)

        val claimedLossless = NerdStats.Snapshot(
            mimeType = "audio/opus",
            bitrateKbps = 128,
            sampleRateHz = 48_000,
            channels = 2,
            claimed = StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24),
        )
        assertFalse(claimedLossless.isLossless)
        assertFalse(claimedLossless.isHiRes)

        val silent = NerdStats.Snapshot(mimeType = "audio/mp4a-latm", bitrateKbps = null, sampleRateHz = null, channels = null)
        assertFalse(silent.isHiQuality)
    }

    @Test
    fun `hi-res is decided on measured depth and rate`() {
        val cd = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 1411,
            sampleRateHz = 44_100,
            channels = 2,
            bitDepth = 16,
        )
        assertTrue(cd.isLossless)
        assertFalse(cd.isHiRes)

        val hiRes = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = 4608,
            sampleRateHz = 96_000,
            channels = 2,
            bitDepth = 24,
        )
        assertTrue(hiRes.isHiRes)

        val unmeasured = NerdStats.Snapshot(
            mimeType = null,
            bitrateKbps = null,
            sampleRateHz = null,
            channels = null,
            claimed = StreamFormat(codec = "flac", sampleRateHz = 192_000, bitDepth = 24),
        )
        assertFalse(unmeasured.isLossless)
        assertFalse(unmeasured.isHiRes)
    }

    private fun song(title: String, artist: String, duration: String? = null, album: String? = null) =
        Song(videoId = "x", title = title, artist = artist, thumbnailUrl = null, durationText = duration, albumName = album)

    private fun matches(candidate: Song, title: String, artist: String, durationSec: Int? = null) =
        TrackMatcher.matches(candidate, title, artist, durationSec)

    @Test
    fun `matches the same recording across differing catalogue titles`() {
        assertTrue(
            matches(
                song("Bohemian Rhapsody (Remastered 2011)", "Queen"),
                title = "Bohemian Rhapsody",
                artist = "Queen",
            ),
        )
        assertTrue(
            matches(
                song("Sunflower", "Post Malone, Swae Lee"),
                title = "Sunflower (feat. Swae Lee)",
                artist = "Post Malone",
            ),
        )
        assertTrue(
            matches(
                song("Don't Stop Me Now", "QUEEN"),
                title = "Dont Stop Me Now",
                artist = "Queen",
            ),
        )
    }

    @Test
    fun `matches CJK titles across punctuation and spacing differences`() {
        assertTrue(
            matches(
                song("夜に駆ける", "YOASOBI", duration = "4:20"),
                title = "夜 に 駆ける！",
                artist = "YOASOBI",
                durationSec = 261,
            ),
        )
        assertTrue(matches(song("좋은 날", "IU"), "좋은날", "IU"))
    }

    @Test
    fun `keeps substring matching strict for short Unicode titles`() {
        assertFalse(matches(song("愛情", "Artist"), "愛", "Artist"))
        assertFalse(matches(song("봄날", "Artist"), "봄", "Artist"))
    }

    @Test
    fun `matches a film credit against a bare catalogue listing`() {
        assertTrue(
            matches(
                song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", duration = "4:07"),
                title = "Paniyon Sa (From \"Satyameva Jayate\")",
                artist = "Atif Aslam",
                durationSec = 247,
            ),
        )
        assertTrue(
            matches(
                song("Paniyon Sa (From \"Satyameva Jayate\")", "Atif Aslam"),
                title = "Paniyon Sa",
                artist = "Atif Aslam, Tulsi Kumar",
            ),
        )
    }

    @Test
    fun `strips upload labelling from either side`() {
        assertTrue(matches(song("Tum Hi Ho", "Arijit Singh"), "Tum Hi Ho Full Song", "Arijit Singh"))
        assertTrue(
            matches(
                song("Kesariya", "Arijit Singh"),
                title = "Kesariya - Brahmastra | Official Video",
                artist = "Arijit Singh",
            ),
        )
        assertTrue(
            matches(
                song("Believer", "Imagine Dragons"),
                title = "Imagine Dragons - Believer",
                artist = "Imagine Dragons",
            ),
        )
    }

    @Test
    fun `refuses a different song by the same artist`() {
        assertFalse(
            matches(
                song("The Show Must Go On", "Queen"),
                title = "Bohemian Rhapsody",
                artist = "Queen",
            ),
        )
    }

    @Test
    fun `refuses a cover by a different artist`() {
        assertFalse(
            matches(
                song("Hurt", "Johnny Cash"),
                title = "Hurt",
                artist = "Nine Inch Nails",
            ),
        )
    }

    @Test
    fun `accepts a shared artist when catalogues credit differently`() {
        assertTrue(
            matches(
                song("Numb / Encore", "Jay-Z & Linkin Park"),
                title = "Numb / Encore",
                artist = "Linkin Park",
            ),
        )
    }

    @Test
    fun `accepts a composer credit against a singer credit on an exact runtime`() {
        assertTrue(
            matches(
                song("Jhak Maar Ke", "Neeraj Shridhar", duration = "3:53"),
                title = "Jhak Maar Ke",
                artist = "Pritam",
                durationSec = 233,
            ),
        )
        assertFalse(
            matches(
                song("Jhak Maar Ke", "Leo Lz Mix", duration = "4:01"),
                title = "Jhak Maar Ke",
                artist = "Pritam",
                durationSec = 233,
            ),
        )
    }

    @Test
    fun `will not waive the credit check without a runtime to back it`() {
        assertFalse(matches(song("Jhak Maar Ke", "Neeraj Shridhar"), "Jhak Maar Ke", "Pritam"))
        assertFalse(
            matches(
                song("Jhak Maar Ke", "Neeraj Shridhar", duration = "3:53"),
                title = "Jhak Maar Ke",
                artist = "Pritam",
                durationSec = null,
            ),
        )
    }

    @Test
    fun `prefers a credited match over a runtime-vouched one`() {
        val target = TrackMatcher.Target("Jhak Maar Ke", "Pritam, Neeraj Shridhar", durationSec = 233)
        val vouched = song("Jhak Maar Ke", "Some Uploader", duration = "3:53")
        val credited = song("Jhak Maar Ke", "Neeraj Shridhar", duration = "3:53")
        assertEquals(credited, TrackMatcher.best(listOf(vouched, credited), target))
    }

    @Test
    fun `refuses an artist whose name merely contains the one asked for`() {
        assertFalse(matches(song("No One Knows", "Queens of the Stone Age"), "No One Knows", "Queen"))
    }

    @Test
    fun `refuses a different take of the same song`() {
        assertFalse(matches(song("Shape of You (Acoustic)", "Ed Sheeran"), "Shape of You", "Ed Sheeran"))
        assertFalse(matches(song("Shape of You", "Ed Sheeran"), "Shape of You (Acoustic)", "Ed Sheeran"))
        assertFalse(matches(song("Creep (Live)", "Radiohead"), "Creep", "Radiohead"))
        assertFalse(matches(song("Faded", "Alan Walker"), "Faded (Slowed + Reverb)", "Alan Walker"))
        assertFalse(
            matches(
                song("Apna Bana Le - Arijit Singh Vocals Only", "Arijit Singh, Sachin-Jigar"),
                title = "Apna Bana Le (From \"Bhediya\")",
                artist = "Arijit Singh",
            ),
        )
        assertFalse(matches(song("Kesariya (Instrumental)", "Arijit Singh"), "Kesariya", "Arijit Singh"))
        assertTrue(matches(song("Creep (Live)", "Radiohead"), "Creep [Live]", "Radiohead"))
    }

    @Test
    fun `treats an album or radio version as the plain track`() {
        assertTrue(matches(song("Africa", "Toto"), "Africa (Album Version)", "Toto"))
        assertTrue(matches(song("Clocks", "Coldplay"), "Clocks (Radio Edit)", "Coldplay"))
    }

    @Test
    fun `refuses a candidate whose runtime is nowhere near`() {
        assertFalse(
            matches(
                song("Levitating", "Dua Lipa", duration = "1:00:12"),
                title = "Levitating",
                artist = "Dua Lipa",
                durationSec = 203,
            ),
        )
        assertTrue(
            matches(
                song("Levitating", "Dua Lipa", duration = "3:25"),
                title = "Levitating",
                artist = "Dua Lipa",
                durationSec = 203,
            ),
        )
    }

    @Test
    fun `falls back to title alone when no artist is known`() {
        assertTrue(matches(song("Clair de Lune", "Debussy"), "Clair de Lune", ""))
        assertFalse(matches(song("Reverie", "Debussy"), "Clair de Lune", ""))
    }

    @Test
    fun `picks the closest candidate rather than the first acceptable one`() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam", durationSec = 247)
        val wrongLength = song("Paniyon Sa", "Atif Aslam", duration = "4:32")
        val right = song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", duration = "4:06")
        assertEquals(right, TrackMatcher.best(listOf(wrongLength, right), target))
    }

    @Test
    fun `uses the album to separate duplicate JioSaavn recordings`() {
        val target = TrackMatcher.Target(
            "Brown Rang",
            "Yo Yo Honey Singh",
            durationSec = 175,
            album = "International Villager",
        )
        val wanted = song("Brown Rang", "Yo Yo Honey Singh", duration = "2:59")
            .copy(albumName = "International Villager")
        val wrongButCloser = song("Brown Rang", "Yo Yo Honey Singh", duration = "2:54")
            .copy(albumName = "Chaar Ikke")

        assertEquals(wanted, TrackMatcher.best(listOf(wrongButCloser, wanted), target))
    }

    @Test
    fun `detects conflicting releases when the requested album is unknown`() {
        val target = TrackMatcher.Target("Brown Rang", "Yo Yo Honey Singh", durationSec = 175)
        val internationalVillager = song("Brown Rang", "Yo Yo Honey Singh", "2:59")
            .copy(albumName = "International Villager")
        val chaarIkke = song("Brown Rang", "Yo Yo Honey Singh", "2:54")
            .copy(albumName = "Chaar Ikke")

        assertTrue(
            TrackMatcher.hasConflictingAlbums(
                listOf(internationalVillager, chaarIkke),
                target,
            ),
        )
        assertFalse(
            TrackMatcher.hasConflictingAlbums(
                listOf(
                    internationalVillager,
                    internationalVillager.copy(albumName = "International Villager (Deluxe Edition)"),
                ),
                target,
            ),
        )
        assertFalse(
            TrackMatcher.hasConflictingAlbums(
                listOf(internationalVillager, chaarIkke),
                target.copy(album = "International Villager"),
            ),
        )
    }

    @Test
    fun `uses the uniquely fullest credit to resolve a JioSaavn release collision`() {
        val target = TrackMatcher.Target("Ek Dil Ek Jaan", "Shivam Pathak", durationSec = 220)
        val original = song(
            "Ek Dil Ek Jaan",
            "Shivam Pathak, Mujtaba Aziz Naza, Kunal Pandit, Farhan Sabri",
            "3:40",
        ).copy(albumName = "Padmaavat")
        val compilation = song("Ek Dil Ek Jaan", "Shivam Pathak", "3:39")
            .copy(albumName = "Top 20 - Romantic Songs 2018")
        val fromCompilation = song(
            "Ek Dil Ek Jaan (From Padmaavat)",
            "Shivam Pathak, Sanjay Leela Bhansali, A.M. Turaz",
            "3:39",
        ).copy(albumName = "Bollywood Magic Mix")

        assertEquals(
            original,
            TrackMatcher.uniquelyMostCreditedCloseMatch(
                listOf(original, compilation, fromCompilation),
                target,
            ),
        )
    }

    @Test
    fun `retains JioSaavn refusal when conflicting releases tie on credit coverage`() {
        val target = TrackMatcher.Target("Mere Bina", "Pritam, Nikhil D'Souza", durationSec = 290)
        val first = song("Mere Bina", "Pritam, Nikhil D'Souza", "4:49").copy(albumName = "Crook")
        val second = song("Mere Bina", "Pritam, Nikhil D'Souza", "4:51").copy(albumName = "Sad Love Hits")

        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(first, second), target))
    }

    @Test
    fun `target keeps the queued album identity`() {
        val queued = song("Brown Rang", "Yo Yo Honey Singh", "2:59")
            .copy(albumName = "International Villager")
        assertEquals("International Villager", TrackMatcher.targetOf(queued).album)
    }

    @Test
    fun `JioSaavn prioritizes the uncensored duplicate`() {
        val clean = RawSongItem(id = "clean", title = "Starboy", explicitContent = "0")
        val explicit = RawSongItem(id = "explicit", title = "Starboy", explicitContent = "1")
        val anotherClean = RawSongItem(id = "clean-2", title = "Starboy", explicitContent = "0")

        assertEquals(
            listOf("explicit", "clean", "clean-2"),
            prioritizeExplicit(listOf(clean, explicit, anotherClean)).map { it.id },
        )
    }

    @Test
    fun `JioSaavn accepts textual explicit flags defensively`() {
        assertTrue(RawSongItem(explicitContent = "true").isExplicit)
        assertFalse(RawSongItem(explicitContent = "false").isExplicit)
        assertFalse(RawSongItem(explicitContent = "").isExplicit)
    }

    @Test
    fun `JioSaavn is off by default on a fresh install`() {
        assertFalse(SourceConfig(kind = SourceKind.JIOSAAVN).enabled)
        val sources = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = true)

        assertFalse(sources.single { it.kind == SourceKind.JIOSAAVN }.enabled)
        assertTrue(sources.single { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    @Test
    fun `the opt-in migration disables JioSaavn once for existing installs`() {
        val previouslyEnabled = SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true)

        val migrated = SourceRegistry.sourcesForInit(
            listOf(previouslyEnabled),
            forceJioSaavnOff = true,
        )
        assertFalse(migrated.single { it.kind == SourceKind.JIOSAAVN }.enabled)

        val userEnabledAgain = migrated.map {
            if (it.kind == SourceKind.JIOSAAVN) it.copy(enabled = true) else it
        }
        val nextLaunch = SourceRegistry.sourcesForInit(
            userEnabledAgain,
            forceJioSaavnOff = false,
        )
        assertTrue(nextLaunch.single { it.kind == SourceKind.JIOSAAVN }.enabled)
    }

    @Test
    fun `disabled JioSaavn and addons are excluded from the source list used by downloads`() {
        val disabledJio = SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false)
        val disabledAddon = SourceConfig(
            kind = SourceKind.ADDON,
            baseUrl = "https://disabled.example",
            enabled = false,
        )
        val enabledAddon = SourceConfig(
            kind = SourceKind.ADDON,
            baseUrl = "https://enabled.example",
            enabled = true,
        )
        val youtube = SourceConfig(kind = SourceKind.YOUTUBE)

        val enabled = SourceRegistry.enabledConfigs(
            listOf(disabledJio, disabledAddon, enabledAddon, youtube),
        )

        assertEquals(listOf(enabledAddon.id, youtube.id), enabled.map { it.id })
    }

    @Test
    fun `JioSaavn upgrades a parameterized 96kbps CDN URL without losing its query`() {
        val url = "https://aac.saavncdn.com/871/song_96.mp4?Expires=123&Signature=abc"

        val selected = selectBestSaavnStream(url, supports320 = true)!!

        assertEquals(
            "https://aac.saavncdn.com/871/song_320.mp4?Expires=123&Signature=abc",
            selected.url,
        )
        assertEquals(320, selected.kbps)
    }

    @Test
    fun `JioSaavn never calls an unrecognised unchanged URL 320kbps`() {
        val url = "https://aac.saavncdn.com/871/song.mp4?token=abc"

        val selected = selectBestSaavnStream(url, supports320 = true)!!

        assertEquals(url, selected.url)
        assertNull(selected.kbps)
    }

    @Test
    fun `explicit YouTube track cannot match the censored JioSaavn edition`() {
        val target = TrackMatcher.Target(
            "Starboy",
            "The Weeknd",
            durationSec = 230,
            album = "Starboy",
            isExplicit = true,
        )
        val clean = song("Starboy", "The Weeknd", "3:50")
            .copy(albumName = "Starboy", isExplicit = false)
        val uncensored = clean.copy(videoId = "uncensored", isExplicit = true)

        assertEquals(uncensored, TrackMatcher.best(listOf(clean, uncensored), target))
        assertNull(TrackMatcher.score(clean, target))
    }

    @Test
    fun `unknown YouTube explicit state does not reject the credited JioSaavn track`() {
        val target = TrackMatcher.Target(
            "For A Reason",
            "Karan Aujla, IKKY",
            durationSec = 180,
            isExplicit = null,
        )
        val wanted = song(
            "For A Reason",
            "Karan Aujla, IKKY, Ikwinder Sahota, Milan D'Agostini",
            "3:00",
        ).copy(videoId = "vLSaC03b", albumName = "P-POP CULTURE", isExplicit = true)
        val remix = song("For A Reason", "Aye Manny", "3:00")
            .copy(videoId = "sN24LH3L", albumName = "For A Reason (Remix)", isExplicit = false)

        assertEquals(wanted, TrackMatcher.best(listOf(remix, wanted), target))
        assertEquals(listOf(wanted), TrackMatcher.ranked(listOf(remix, wanted), target))
    }

    @Test
    fun `missing YouTube badge does not reject explicit I Really Do`() {
        val target = TrackMatcher.Target(
            "I Really Do...",
            "Karan Aujla",
            durationSec = 193,
            isExplicit = null,
        )
        val wanted = song(
            "I Really Do...",
            "Karan Aujla, IKKY, Ikwinder Sahota, Jamal Europe",
            "3:13",
        ).copy(videoId = "1vja2Ptl", albumName = "P-POP CULTURE", isExplicit = true)
        val otherSong = song("I Really Do...", "Arjun Viraat, Shefali Alvares", "3:02")
            .copy(videoId = "WeCT149y", albumName = "I Really Do...", isExplicit = false)

        assertEquals(wanted, TrackMatcher.best(listOf(otherSong, wanted), target))
    }

    @Test
    fun `unlabelled music video timing cannot make another artist the Brown Rang match`() {
        val target = TrackMatcher.Target(
            "Brown Rang",
            "Yo Yo Honey Singh",
            durationSec = 211,
            isVideo = false,
        )
        val wantedAudio = song("Brown Rang", "Yo Yo Honey Singh", "2:59")
            .copy(videoId = "vj2tW1iy", albumName = "International Villager")
        val wrongExactRuntime = song("Brown Rang", "Lovely, Jais Rikhi, Love Sagar", "3:30")
            .copy(videoId = "UgOpg53G", albumName = "Brown Rang")

        assertEquals(wantedAudio, TrackMatcher.best(listOf(wrongExactRuntime, wantedAudio), target))
        assertEquals(
            listOf(wantedAudio),
            TrackMatcher.ranked(listOf(wrongExactRuntime, wantedAudio), target),
        )
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(wantedAudio), target))
    }

    @Test
    fun `manual video audio switch accepts the official song despite a long visual intro`() {
        val target = TrackMatcher.Target(
            "Big Dawgs",
            "Hanumankind, Kalmi",
            durationSec = 391,
            isVideo = true,
        )
        val officialAudio = song("Big Dawgs", "Hanumankind, Kalmi", "3:11")
            .copy(videoId = "official-audio")
        val sameTitleCover = song("Big Dawgs", "Unrelated Cover Artist", "6:31")
            .copy(videoId = "cover")

        assertNull(TrackMatcher.best(listOf(officialAudio, sameTitleCover), target))
        assertEquals(
            officialAudio,
            TrackMatcher.bestOfficialAudioForVideo(listOf(sameTitleCover, officialAudio), target),
        )
    }

    @Test
    fun `refuses to let a lossless label outrank the right runtime`() {
        val target = TrackMatcher.Target("Sakhiyaan", "Maninder Buttar", durationSec = 180)
        val djEdit = song("Sakhiyaan", "Maninder Buttar", duration = "3:05")
            .copy(albumName = "Punjabi Dj Holi songs", sourceQuality = "LOSSLESS")
        val albumCut = song("Sakhiyaan", "Maninder Buttar", duration = "3:00")
            .copy(albumName = "Sakhiyaan")
        assertTrue(TrackMatcher.matches(djEdit, target.title, target.artist))
        assertEquals(albumCut, TrackMatcher.best(listOf(djEdit, albumCut), target))
        assertEquals(
            listOf(albumCut),
            SourceResolver.preferred(listOf(djEdit, albumCut), target, wantsLossless = true),
        )
    }

    @Test
    fun `prefers the lossless copy when nothing separates the recordings`() {
        val target = TrackMatcher.Target("Sakhiyaan", "Maninder Buttar", durationSec = 180)
        val plain = song("Sakhiyaan", "Maninder Buttar", duration = "3:00")
        val lossless = song("Sakhiyaan", "Maninder Buttar", duration = "3:00")
            .copy(sourceQuality = "LOSSLESS")
        assertEquals(
            listOf(lossless, plain),
            SourceResolver.preferred(listOf(plain, lossless), target, wantsLossless = true),
        )
    }

    @Test
    fun `prefers the immersive row over a lossless one when Atmos is wanted`() {
        DeviceCodecs.forced = true
        AppSettings.dolbyAtmos.value = true
        try {
            val target = TrackMatcher.Target("Gehra Hua", "Shashwat Sachdev", durationSec = 362)
            val stereo = song("Gehra Hua", "Shashwat Sachdev", duration = "6:02")
                .copy(sourceQuality = ModuleSource.LOSSLESS)
            val atmos = song("Gehra Hua", "Shashwat Sachdev", duration = "6:02")
                .copy(sourceQuality = ModuleSource.DOLBY)

            assertEquals(
                listOf(atmos, stereo),
                SourceResolver.preferred(listOf(stereo, atmos), target, wantsLossless = true),
            )
        } finally {
            DeviceCodecs.forced = null
        }
    }

    @Test
    fun `ignores the immersive row when the Atmos setting is off`() {
        DeviceCodecs.forced = true
        AppSettings.dolbyAtmos.value = false
        try {
            val target = TrackMatcher.Target("Gehra Hua", "Shashwat Sachdev", durationSec = 362)
            val stereo = song("Gehra Hua", "Shashwat Sachdev", duration = "6:02")
                .copy(sourceQuality = ModuleSource.LOSSLESS)
            val atmos = song("Gehra Hua", "Shashwat Sachdev", duration = "6:02")
                .copy(sourceQuality = ModuleSource.DOLBY)

            assertEquals(
                listOf(stereo, atmos),
                SourceResolver.preferred(listOf(stereo, atmos), target, wantsLossless = true),
            )
        } finally {
            DeviceCodecs.forced = null
            AppSettings.dolbyAtmos.value = true
        }
    }

    @Test
    fun `always swaps to lossless`() {
        val youtube = StreamFormat(codec = "opus", kbps = 160)
        assertTrue(SourceResolver.worthSwapping(StreamFormat(codec = "flac"), youtube))
        assertTrue(
            SourceResolver.worthSwapping(StreamFormat(codec = "flac"), StreamFormat(codec = "aac", kbps = 320)),
        )
    }

    @Test
    fun `swaps to a lossy stream that is clearly better than what is playing`() {
        val youtube = StreamFormat(codec = "opus", kbps = 160)
        assertTrue(SourceResolver.worthSwapping(StreamFormat(codec = "aac", kbps = 320), youtube))
    }

    @Test
    fun `refuses a lossy swap that gains little`() {
        assertFalse(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "mp3", kbps = 192),
                StreamFormat(codec = "aac", kbps = 128),
            ),
        )
        assertFalse(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "mp3", kbps = 128),
                StreamFormat(codec = "opus", kbps = 160),
            ),
        )
    }

    @Test
    fun `refuses a lossy swap it cannot measure`() {
        val playing = StreamFormat(codec = "opus", kbps = 160)
        assertFalse(SourceResolver.worthSwapping(StreamFormat(codec = "aac"), playing))
        assertFalse(SourceResolver.worthSwapping(StreamFormat(codec = "aac", kbps = 320), null))
        assertFalse(
            SourceResolver.worthSwapping(StreamFormat(codec = "aac", kbps = 320), StreamFormat(codec = "opus")),
        )
    }

    @Test
    fun `ranks a higher-bitrate lossy stream above a lower one`() {
        assertTrue(
            SourceResolver.isBetter(
                StreamFormat(codec = "mp4", kbps = 320),
                StreamFormat(codec = "mp3", kbps = 128),
            ),
        )
        assertFalse(
            SourceResolver.isBetter(
                StreamFormat(codec = "mp3", kbps = 128),
                StreamFormat(codec = "mp4", kbps = 320),
            ),
        )
    }

    @Test
    fun `ranks lossless above any lossy bitrate`() {
        assertTrue(
            SourceResolver.isBetter(
                StreamFormat(codec = "flac"),
                StreamFormat(codec = "mp4", kbps = 320),
            ),
        )
        assertFalse(
            SourceResolver.isBetter(
                StreamFormat(codec = "mp4", kbps = 320),
                StreamFormat(codec = "flac"),
            ),
        )
    }

    @Test
    fun `ranks an immersive mix above a lossless copy`() {
        assertTrue(
            SourceResolver.isBetter(
                StreamFormat(codec = "eac3-joc", sampleRateHz = 48000),
                StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96000),
            ),
        )
        assertFalse(
            SourceResolver.isBetter(
                StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96000),
                StreamFormat(codec = "eac3-joc", sampleRateHz = 48000),
            ),
        )
    }

    @Test
    fun `never swaps away from an immersive mix that is already playing`() {
        assertFalse(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "flac", bitDepth = 24),
                StreamFormat(codec = "eac3-joc"),
            ),
        )
        assertTrue(
            SourceResolver.worthSwapping(
                StreamFormat(codec = "eac3-joc"),
                StreamFormat(codec = "flac", bitDepth = 24),
            ),
        )
    }

    @Test
    fun `anything beats no stream at all`() {
        assertTrue(SourceResolver.isBetter(StreamFormat(codec = "mp3", kbps = 128), null))
    }

    @Test
    fun `an equal stream does not displace the one already held`() {
        assertFalse(
            SourceResolver.isBetter(
                StreamFormat(codec = "mp4", kbps = 320),
                StreamFormat(codec = "aac", kbps = 320),
            ),
        )
        assertFalse(
            SourceResolver.isBetter(StreamFormat(codec = "flac"), StreamFormat(codec = "alac")),
        )
    }

    @Test
    fun `an undescribed lossy stream does not outrank a stated one`() {
        assertFalse(
            SourceResolver.isBetter(StreamFormat(codec = "aac"), StreamFormat(codec = "mp4", kbps = 320)),
        )
        assertTrue(
            SourceResolver.isBetter(StreamFormat(codec = "mp4", kbps = 320), StreamFormat(codec = "aac")),
        )
    }

    @Test
    fun `has nothing to offer when no candidate is the recording`() {
        val target = TrackMatcher.Target("Paniyon Sa", "Atif Aslam")
        assertNull(TrackMatcher.best(listOf(song("Paniyon Sa", "Some Cover Band")), target))
        assertNull(TrackMatcher.best(emptyList(), target))
    }

    @Test
    fun `a 320 from a source is worth keeping over youtube's aac`() {
        assertTrue(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "mp4", kbps = 320)))
    }

    @Test
    fun `a thinner rendition loses to youtube's aac`() {
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "mp3", kbps = 128)))
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "mp4", kbps = 160)))
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "aac", kbps = 256)))
    }

    @Test
    fun `an unstated rendition is not worth keeping`() {
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat()))
        assertFalse(SourceResolver.beatsYouTubeAac(StreamFormat(codec = "mp4")))
    }

    @Test
    fun `only the quick source is worth resolving ahead of playback`() {
        assertTrue(SourceKind.JIOSAAVN.worthPrefetching)
        assertFalse(SourceKind.MODULE.worthPrefetching)
        assertFalse(SourceKind.CUSTOM_MODULE.worthPrefetching)
    }

    @Test
    fun `youtube is not prefetched as a substitute for itself`() {
        assertFalse(SourceKind.YOUTUBE.worthPrefetching)
    }

    private class FakeSource(
        override val displayName: String,
        private val answerAfterMs: Long,
        private val format: StreamFormat?,
        override val kind: SourceKind = SourceKind.JIOSAAVN,
        private val candidates: List<Song>? = null,
    ) : MusicSource {
        override val configId = displayName
        var asked = false
            private set
        var cancelled = false
            private set

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            asked = true
            try {
                delay(answerAfterMs)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            if (format == null) return emptyList()
            return candidates ?: listOf(
                Song(
                    videoId = "$displayName-1",
                    title = RACE_TITLE,
                    artist = RACE_ARTIST,
                    thumbnailUrl = null,
                    durationText = "2:00",
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            format?.let { SourceStream(url = "https://$displayName/$trackId", format = it) }
    }

    private fun raceTarget() = TrackMatcher.Target(RACE_TITLE, RACE_ARTIST, durationSec = 120)

    private class RowSource(
        private val rows: List<Pair<Song, StreamFormat>>,
    ) : MusicSource {
        override val displayName = "Soundlink"
        override val configId = displayName
        override val kind = SourceKind.ADDON

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = rows.map { it.first }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            rows.firstOrNull { it.first.videoId == trackId }
                ?.let { (_, format) -> SourceStream(url = "https://soundlink/$trackId", format = format) }
    }

    private fun takeOnMeRows() = RowSource(
        listOf(
            song("Take on Me", "a-ha", "3:45").copy(videoId = "album-cut", sourceQuality = "LOSSLESS") to
                StreamFormat("flac", sampleRateHz = 44_100, bitDepth = 16),
            song("Take on Me", "a-ha", "3:48").copy(videoId = "remaster", sourceQuality = "LOSSLESS") to
                StreamFormat("flac", sampleRateHz = 192_000, bitDepth = 24),
        ),
    )

    private val takeOnMe = TrackMatcher.Target("Take on Me", "a-ha", durationSec = 225)

    @Test
    fun `upgrade takes the hi-res copy inside the listener's length tolerance`() = runBlocking {
        val (_, stream) = SourceResolver.bestAcross(
            listOf(takeOnMeRows()),
            takeOnMe,
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = 5,
        )!!
        assertEquals(24, stream.format.bitDepth)
        assertEquals(192_000, stream.format.sampleRateHz)
    }

    @Test
    fun `upgrade under a two second bar never sees the remaster`() = runBlocking {
        val (_, stream) = SourceResolver.bestAcross(
            listOf(takeOnMeRows()),
            takeOnMe,
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = 2,
        )!!
        assertEquals(16, stream.format.bitDepth)
    }

    @Test
    fun `ranks hi-res lossless above CD quality`() {
        val hiRes = StreamFormat("flac", sampleRateHz = 192_000, bitDepth = 24)
        val cd = StreamFormat("flac", sampleRateHz = 44_100, bitDepth = 16)
        assertTrue(SourceResolver.isBetter(hiRes, cd))
        assertFalse(SourceResolver.isBetter(cd, hiRes))
        assertTrue(
            SourceResolver.isBetter(
                StreamFormat("flac", sampleRateHz = 192_000, bitDepth = 24),
                StreamFormat("flac", sampleRateHz = 96_000, bitDepth = 24),
            ),
        )
    }

    @Test
    fun `prefers the hi-res row among lossless ones`() {
        val target = TrackMatcher.Target("Take on Me", "a-ha", durationSec = 225)
        val cd = song("Take on Me", "a-ha", "3:45").copy(sourceQuality = "LOSSLESS")
        val hiRes = song("Take on Me", "a-ha", "3:45").copy(sourceQuality = "LOSSLESS", sourceHiRes = true)
        assertEquals(listOf(hiRes, cd), SourceResolver.preferred(listOf(cd, hiRes), target, wantsLossless = true))
    }

    @Test
    fun `JioSaavn substitution refuses conflicting albums without a target album`() = runBlocking {
        val source = FakeSource(
            displayName = "JioSaavn",
            answerAfterMs = 0,
            format = StreamFormat("mp4", kbps = 320),
            candidates = listOf(
                song("Brown Rang", "Yo Yo Honey Singh", "2:59")
                    .copy(videoId = "international-villager", albumName = "International Villager"),
                song("Brown Rang", "Yo Yo Honey Singh", "2:54")
                    .copy(videoId = "chaar-ikke", albumName = "Chaar Ikke"),
            ),
        )
        val target = TrackMatcher.Target("Brown Rang", "Yo Yo Honey Singh", durationSec = 175)

        assertNull(SourceResolver.bestAcross(listOf(source), target, StreamRequest.Best))
    }

    @Test
    fun `takes the quick answer rather than waiting for a slow better one`() = runBlocking {
        val slow = FakeSource("Ricky's Addon", answerAfterMs = 2_000, format = StreamFormat("flac"))
        val quick = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        val elapsed = measureTimeMillis {
            val (source, stream) = SourceResolver.bestAcross(
                listOf(slow, quick), raceTarget(), StreamRequest.Lossless,
            )!!
            assertEquals("JioSaavn", source.displayName)
            assertEquals(320, stream.format.kbps)
        }
        assertTrue("took ${elapsed}ms, so it waited for the slow source", elapsed < 1_000)
        assertTrue(slow.asked)
    }

    @Test
    fun `abandons the sources still running once it has an answer`() = runBlocking {
        val slow = FakeSource("Ricky's Addon", answerAfterMs = 2_000, format = StreamFormat("flac"))
        val quick = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        SourceResolver.bestAcross(listOf(slow, quick), raceTarget(), StreamRequest.Lossless)
        assertTrue("the slow source was left running", slow.cancelled)
    }

    @Test
    fun `prefers the better of two answers that arrive together`() = runBlocking {
        val worse = FakeSource("Ricky's Addon", answerAfterMs = 5, format = StreamFormat("mp3", kbps = 128))
        val better = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        val (source, stream) = SourceResolver.bestAcross(
            listOf(worse, better), raceTarget(), StreamRequest.Lossless,
        )!!
        assertEquals("JioSaavn", source.displayName)
        assertEquals(320, stream.format.kbps)
    }

    @Test
    fun `keeps waiting when the first source to answer has nothing`() = runBlocking {
        val empty = FakeSource("Ricky's Addon", answerAfterMs = 5, format = null)
        val holder = FakeSource("JioSaavn", answerAfterMs = 200, format = StreamFormat("mp4", kbps = 320))
        val (source, _) = SourceResolver.bestAcross(
            listOf(empty, holder), raceTarget(), StreamRequest.Lossless,
        )!!
        assertEquals("JioSaavn", source.displayName)
    }

    @Test
    fun `has nothing when no source holds the track`() = runBlocking {
        val a = FakeSource("Ricky's Addon", answerAfterMs = 5, format = null)
        val b = FakeSource("JioSaavn", answerAfterMs = 10, format = null)
        assertNull(SourceResolver.bestAcross(listOf(a, b), raceTarget(), StreamRequest.Lossless))
    }

    @Test
    fun `patient upgrade chooses the better later answer`() = runBlocking {
        val playing = StreamFormat(codec = "opus", kbps = 141)
        val slowLossless = FakeSource("Ricky's Addon", answerAfterMs = 200, format = StreamFormat("flac"))
        val quickLossy = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        val (source, stream) = SourceResolver.bestAcross(
            listOf(slowLossless, quickLossy),
            raceTarget(),
            StreamRequest.Lossless,
            waitForAll = true,
            strictLength = true,
        ) { _, candidate -> SourceResolver.worthSwapping(candidate.format, playing) }!!
        assertEquals("Ricky's Addon", source.displayName)
        assertEquals("flac", stream.format.codec)
        assertFalse("the patient lookup cancelled the later source", slowLossless.cancelled)
    }

    @Test
    fun `upgrade refuses same title and runtime from a different artist`() = runBlocking {
        val wrongMexico = FakeSource(
            displayName = "Ricky's Addon",
            answerAfterMs = 0,
            format = StreamFormat("flac"),
            candidates = listOf(song("Mexico", "CAKE", "3:26")),
        )
        val target = TrackMatcher.Target("Mexico", "Karan Aujla", durationSec = 207)

        assertNull(
            SourceResolver.bestAcross(
                listOf(wrongMexico),
                target,
                StreamRequest.Lossless,
                waitForAll = true,
                strictLength = true,
                requireSharedArtist = true,
            ),
        )
    }

    @Test
    fun `keeps waiting when the quick answer is refused`() = runBlocking {
        val playing = StreamFormat(codec = "opus", kbps = 141)
        val quickRefused = FakeSource("Ricky's Addon", answerAfterMs = 5, format = StreamFormat("mp3", kbps = 128))
        val slowTaken = FakeSource("JioSaavn", answerAfterMs = 200, format = StreamFormat("mp4", kbps = 320))
        val (source, _) = SourceResolver.bestAcross(
            listOf(quickRefused, slowTaken),
            raceTarget(),
            StreamRequest.Lossless,
        ) { _, candidate -> SourceResolver.worthSwapping(candidate.format, playing) }!!
        assertEquals("JioSaavn", source.displayName)
    }

    @Test
    fun `a tie goes to the higher-ranked source rather than the quicker one`() = runBlocking {
        val addon = FakeSource("Ricky's Addon", answerAfterMs = 150, format = StreamFormat("aac", kbps = 320))
        val jioSaavn = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        val (source, _) = SourceResolver.bestAcross(
            listOf(addon, jioSaavn),
            raceTarget(),
            StreamRequest.Best,
            waitForAll = true,
        )!!
        assertEquals("Ricky's Addon", source.displayName)
    }

    @Test
    fun `a race that runs out of time keeps the answers already in hand`() = runBlocking {
        val slow = FakeSource("Ricky's Addon", answerAfterMs = 5_000, format = StreamFormat("flac"))
        val quick = FakeSource("JioSaavn", answerAfterMs = 5, format = StreamFormat("mp4", kbps = 320))
        val elapsed = measureTimeMillis {
            val (source, _) = SourceResolver.bestAcross(
                listOf(slow, quick),
                raceTarget(),
                StreamRequest.Lossless,
                waitForAll = true,
                timeoutMs = 300,
            )!!
            assertEquals("JioSaavn", source.displayName)
        }
        assertTrue("took ${elapsed}ms, so it waited out the slow source", elapsed < 2_000)
        assertTrue("the slow source was left running", slow.cancelled)
    }

    @Test
    fun `a refined-away candidate leaves the race to the next one`() = runBlocking {
        val unkeepable = FakeSource("Ricky's Addon", answerAfterMs = 5, format = StreamFormat("flac"))
        val keepable = FakeSource("JioSaavn", answerAfterMs = 50, format = StreamFormat("mp4", kbps = 320))
        val (source, _) = SourceResolver.bestAcross(
            listOf(unkeepable, keepable),
            raceTarget(),
            StreamRequest.Lossless,
            waitForAll = true,
            refine = { candidate, stream -> stream.takeIf { candidate !== unkeepable } },
        )!!
        assertEquals("JioSaavn", source.displayName)
    }

    @Test
    fun `asks for the title a catalogue would file the track under`() {
        val queries = TrackMatcher.queries(
            TrackMatcher.Target("Paniyon Sa (From \"Satyameva Jayate\") | Official Video", "Atif Aslam, Tulsi Kumar"),
        )
        assertEquals(listOf("paniyon sa atif aslam", "paniyon sa"), queries)
    }

    @Test
    fun `keeps the version marker in the query`() {
        assertEquals(
            "shape of you acoustic",
            TrackMatcher.queries(TrackMatcher.Target("Shape of You (Acoustic)", "")).single(),
        )
    }

    @Test
    fun `drops a Portuguese guest credit from the query`() {
        assertEquals(
            listOf("o mundo é mal froid", "o mundo é mal"),
            TrackMatcher.queries(TrackMatcher.Target("O Mundo É Mal (part. Clara Lima)", "Froid")),
        )
        assertEquals(
            "song part",
            TrackMatcher.queries(TrackMatcher.Target("Song (Part 1)", "")).single(),
        )
        assertEquals(
            listOf("part of me"),
            TrackMatcher.queries(TrackMatcher.Target("Part of Me", "")),
        )
    }

    @Test
    fun `ignores a YouTube type label in the artist slot`() {
        assertEquals(
            "lud session 2",
            TrackMatcher.queries(TrackMatcher.Target("Lud Session 2", "Música")).single(),
        )
    }

    @Test
    fun `has nothing to ask for without a title`() {
        assertTrue(TrackMatcher.queries(TrackMatcher.Target("", "Atif Aslam")).isEmpty())
    }

    @Test
    fun `asks for the romaji filing of a mixed-script title`() {
        assertEquals(
            listOf("dai zero kan 10-feet", "dai zero kan"),
            TrackMatcher.queries(TrackMatcher.Target("第ゼロ感 - Dai Zero Kan", "10-FEET")),
        )
    }

    @Test
    fun `keeps a pure CJK title instead of asking for nothing`() {
        assertEquals(
            listOf("残響散歌 aimer", "残響散歌"),
            TrackMatcher.queries(TrackMatcher.Target("残響散歌", "Aimer")),
        )
    }

    @Test
    fun `matches the romaji catalogue row for a mixed-script request`() {
        assertTrue(
            matches(
                song("Dai Zero Kan", "10-FEET", "4:48"),
                title = "第ゼロ感 - Dai Zero Kan",
                artist = "10-FEET",
                durationSec = 288,
            ),
        )
    }

    @Test
    fun `rejects the cover take of a CJK recording`() {
        assertFalse(
            matches(
                song("第ゼロ感 (Cover)", "Raise A Suilen", "4:49"),
                title = "第ゼロ感",
                artist = "10-FEET",
                durationSec = 288,
            ),
        )
        assertFalse(
            matches(
                song("残響散歌 (カバー)", "Aimer", "4:49"),
                title = "残響散歌",
                artist = "Aimer",
                durationSec = 288,
            ),
        )
    }

    @Test
    fun `matches a bracketed CJK translation against its Latin filing`() {
        assertTrue(
            matches(
                song("Love", "Singer", "2:53"),
                title = "愛(Love)",
                artist = "Singer",
                durationSec = 174,
            ),
        )
        assertTrue(
            matches(
                song("Love (愛)", "Singer", "2:53"),
                title = "愛",
                artist = "Singer",
                durationSec = 174,
            ),
        )
        assertFalse(
            matches(
                song("Film Name", "Some Artist", "4:07"),
                title = "Song Name (From \"Film Name\")",
                artist = "Singer",
                durationSec = 247,
            ),
        )
        assertFalse(
            matches(
                song("Night", "Singer", "4:20"),
                title = "夜に駆ける (Racing Into The Night)",
                artist = "Singer",
                durationSec = 260,
            ),
        )
    }

    @Test
    fun `accepts the remix single whose marker lives on the album`() {
        assertTrue(
            matches(
                song("KALYANI", "ARJN, KDS, FIFTY4, Shreya Ghoshal", "4:29", album = "KALYANI (Remix)"),
                title = "KALYANI (Remix)",
                artist = "ARJN, KDS, FIFTY4 & Shreya Ghoshal",
                durationSec = 270,
            ),
        )
    }

    @Test
    fun `still rejects the original recording for a remix request`() {
        assertFalse(
            matches(
                song("KALYANI", "ARJN, KDS, FIFTY4, Shreya Ghoshal", "4:30", album = "KALYANI"),
                title = "KALYANI (Remix)",
                artist = "ARJN, KDS, FIFTY4 & Shreya Ghoshal",
                durationSec = 270,
            ),
        )
    }

    @Test
    fun `a plain request ignores the album when the row needs no marker`() {
        assertTrue(
            matches(
                song("KALYANI", "ARJN, KDS, FIFTY4, Shreya Ghoshal", "4:29", album = "KALYANI (Remix)"),
                title = "KALYANI",
                artist = "ARJN, KDS, FIFTY4 & Shreya Ghoshal",
                durationSec = 269,
            ),
        )
    }

    @Test
    fun `rejects the instrumental twin for a vocal request`() {
        assertFalse(
            matches(
                song(
                    "Am I Dreaming", "Metro Boomin", "4:16",
                    album = "METRO BOOMIN PRESENTS SPIDER-MAN: ACROSS THE SPIDER-VERSE " +
                        "(SOUNDTRACK FROM AND INSPIRED BY THE MOTION PICTURE " +
                        "(METROVERSE INSTRUMENTAL EDITION))",
                ),
                title = "Am I Dreaming",
                artist = "Metro Boomin, A\$AP Rocky & Roisee",
                durationSec = 257,
            ),
        )
    }

    @Test
    fun `accepts the instrumental twin for an instrumental request`() {
        assertTrue(
            matches(
                song(
                    "Am I Dreaming", "Metro Boomin", "4:16",
                    album = "METRO BOOMIN PRESENTS SPIDER-MAN: ACROSS THE SPIDER-VERSE " +
                        "(SOUNDTRACK FROM AND INSPIRED BY THE MOTION PICTURE " +
                        "(METROVERSE INSTRUMENTAL EDITION))",
                ),
                title = "Am I Dreaming (Instrumental)",
                artist = "Metro Boomin",
                durationSec = 257,
            ),
        )
    }

    @Test
    fun `still matches the vocal original from the same soundtrack`() {
        assertTrue(
            matches(
                song(
                    "Am I Dreaming", "Metro Boomin, A\$AP Rocky, Roisee", "4:16",
                    album = "METRO BOOMIN PRESENTS SPIDER-MAN: ACROSS THE SPIDER-VERSE " +
                        "(SOUNDTRACK FROM AND INSPIRED BY THE MOTION PICTURE)",
                ),
                title = "Am I Dreaming",
                artist = "Metro Boomin, A\$AP Rocky & Roisee",
                durationSec = 257,
            ),
        )
    }

    @Test
    fun `matches the plain Mayonaka row across the wave-dash spellings`() {
        assertEquals(
            listOf("真夜中のドア stay miki matsubara", "真夜中のドア stay"),
            TrackMatcher.queries(
                TrackMatcher.Target(
                    "真夜中のドア〜stay with me - Mayonaka no Door~stay with me",
                    "Miki Matsubara",
                ),
            ),
        )
        assertTrue(
            matches(
                song("真夜中のドア〜stay with me", "miki matsubara", "5:12", album = "松原みき ベスト・コレクション"),
                title = "真夜中のドア〜stay with me - Mayonaka no Door~stay with me",
                artist = "Miki Matsubara",
                durationSec = 312,
            ),
        )
    }

    @Test
    fun `matches the club mix take for a club mix request`() {
        assertTrue(
            matches(
                song(
                    "真夜中のドア〜stay with me (Original club mix)", "miki matsubara", "5:46",
                    album = "POCKET PARK (Remastered)",
                ),
                title = "真夜中のドア〜stay with me (Original club mix)",
                artist = "Miki Matsubara",
                durationSec = 346,
            ),
        )
        assertFalse(
            matches(
                song("真夜中のドア〜stay with me", "miki matsubara", "5:12"),
                title = "真夜中のドア〜stay with me (Original club mix)",
                artist = "Miki Matsubara",
                durationSec = 346,
            ),
        )
    }

    @Test
    fun `asks both scripts for a transliterated pair`() {
        assertEquals(
            listOf("koikogare mugi", "koikogare", "コイコガレ"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ - koikogare", "MUGI")),
        )
        assertEquals(
            listOf("コイコガレ mugi", "コイコガレ", "koikogare"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ", "MUGI")),
        )
    }

    @Test
    fun `keeps a CJK title whose dash tail is the artist`() {
        assertEquals(
            listOf("紅蓮華 lisa", "紅蓮華"),
            TrackMatcher.queries(TrackMatcher.Target("紅蓮華 - LiSA", "LiSA")),
        )
    }

    @Test
    fun `matches the katakana row for a romaji-queried Koi Kogare`() {
        assertTrue(
            matches(
                song("コイコガレ", "MUGI", "2:39", album = "LAST KISS"),
                title = "コイコガレ - koikogare",
                artist = "MUGI",
                durationSec = 159,
            ),
        )
        assertFalse(
            matches(
                song("Koi Kogare", "milet, MAN WITH A MISSION", "3:36"),
                title = "コイコガレ - koikogare",
                artist = "MUGI",
                durationSec = 159,
            ),
        )
    }

    @Test
    fun `matches the instrumental twin for an instrumental Kizuna request`() {
        assertTrue(
            matches(
                song(
                    "Kizuna No Kiseki Instrumental", "MAN WITH A MISSION, milet", "3:43",
                    album = "Kizuna No Kiseki / Koi Kogare",
                ),
                title = "絆ノ奇跡 -Instrumental- - Kizuna No Kiseki Instrumental",
                artist = "MAN WITH A MISSION & milet",
                durationSec = 223,
            ),
        )
        assertFalse(
            matches(
                song("Kizuna No Kiseki", "MAN WITH A MISSION, milet", "3:43", album = "Kizuna No Kiseki"),
                title = "絆ノ奇跡 -Instrumental- - Kizuna No Kiseki Instrumental",
                artist = "MAN WITH A MISSION & milet",
                durationSec = 223,
            ),
        )
    }

    @Test
    fun `separates vocal from instrumental blue`() {
        assertTrue(
            matches(
                song("blue (instrumental)", "yung kai", "3:36", album = "shades of blue (instrumental)"),
                title = "blue (instrumental)",
                artist = "yung kai",
                durationSec = 216,
            ),
        )
        assertFalse(
            matches(
                song("blue", "yung kai", "3:36", album = "shades of blue"),
                title = "blue (instrumental)",
                artist = "yung kai",
                durationSec = 216,
            ),
        )
    }

    @Test
    fun `transliterates kana to romaji`() {
        assertEquals("koikogare", TrackMatcher.romajiOf("コイコガレ"))
        assertEquals("abunaikioku", TrackMatcher.romajiOf("アブナイキオク"))
        assertEquals("kiseki", TrackMatcher.romajiOf("きせき"))
        assertEquals("gakkou", TrackMatcher.romajiOf("がっこう"))
        assertEquals("shimbun", TrackMatcher.romajiOf("しんぶん"))
        assertEquals("kyou", TrackMatcher.romajiOf("きょう"))
        assertEquals("第zero感", TrackMatcher.romajiOf("第ゼロ感"))
        assertEquals("doa", TrackMatcher.romajiOf("ドア"))
        assertEquals("sharuru", TrackMatcher.romajiOf("シャルル"))
        assertEquals("janki", TrackMatcher.romajiOf("ジャンキ"))
        assertEquals("chotto", TrackMatcher.romajiOf("ちょっと"))
        assertEquals("jetto", TrackMatcher.romajiOf("ジェット"))
        assertEquals("renai", TrackMatcher.romajiOf("レンアイ"))
    }

    @Test
    fun `matches the romaji row of a kana title with a syllabic n`() {
        assertTrue(matches(song("Renai", "A", "3:30"), "レンアイ", "A", 210))
    }

    @Test
    fun `album naming beyond the wanted take does not refuse the row`() {
        assertTrue(
            matches(song("Song (Remix)", "Artist", "3:30", album = "Song (Remixes)"), "Song (Remix)", "Artist", 210),
        )
        assertTrue(
            matches(song("Song (Live)", "Artist", "3:30", album = "MTV Unplugged"), "Song (Live)", "Artist", 210),
        )
        assertTrue(
            matches(
                song("Song (Acoustic)", "Artist", "3:30", album = "Acoustic Sessions"),
                "Song (Acoustic)", "Artist", 210,
            ),
        )
    }

    @Test
    fun `a title ending in a version word does not take the live recording`() {
        assertFalse(matches(song("Let Me Live (Live)", "Queen", "4:45"), "Let Me Live", "Queen", 285))
        assertTrue(matches(song("Let Me Live", "Queen", "4:45"), "Let Me Live", "Queen", 285))
    }

    @Test
    fun `a katakana full name is one artist`() {
        assertFalse(TrackMatcher.sharesArtist("ジョン・レノン", "ジョン・ウィリアムズ"))
        assertTrue(TrackMatcher.sharesArtist("ジョン・レノン", "ジョン・レノン、オノ・ヨーコ"))
    }

    @Test
    fun `only swaps in a copy of demonstrably the same length`() {
        val playing = TrackMatcher.Target("Jo Tere Sang", "Jeet Gannguli", durationSec = 306)
        assertTrue(TrackMatcher.withinSeconds(song("Jo Tere Sang", "x", "5:06"), playing, 2))
        assertTrue(TrackMatcher.withinSeconds(song("Jo Tere Sang", "x", "5:04"), playing, 2))
        assertFalse(TrackMatcher.withinSeconds(song("Jo Tere Sang", "x", "5:12"), playing, 2))
        assertFalse(TrackMatcher.withinSeconds(song("Jo Tere Sang", "x"), playing, 2))
        assertFalse(
            TrackMatcher.withinSeconds(
                song("Jo Tere Sang", "x", "5:06"),
                TrackMatcher.Target("Jo Tere Sang", "Jeet Gannguli"),
                2,
            ),
        )
    }

    @Test
    fun `rejects a stream URL carrying a second copy of its own origin`() {
        val blob = "eyJhbGciOiJIUzI1NiJ9"
        assertTrue(
            ModuleSource.malformed(
                "https://sp-ad-fa.audio.tidal.com/mediatracks/$blob/" +
                    "https://sp-ad-fa.audio.tidal.com/mediatracks/$blob/0.mp4?token=1756000000~c2ln",
            ),
        )
        assertFalse(
            ModuleSource.malformed(
                "https://sp-ad-fa.audio.tidal.com/mediatracks/$blob/0.mp4?token=1756000000~c2ln",
            ),
        )
    }

    @Test
    fun `accepts a URL that passes another URL along to a proxy`() {
        assertFalse(ModuleSource.malformed("https://cdn.example.com/get?url=https://real.host/f.flac"))
        assertFalse(ModuleSource.malformed("https://cdn.example.com/https://real.host/f.flac"))
    }

    @Test
    fun `rejects a stream URL the player's own parser would refuse`() {
        assertTrue(ModuleSource.malformed("/mediatracks/blob/0.mp4"))
        assertTrue(ModuleSource.malformed("sp-ad-fa.audio.tidal.com/mediatracks/blob/0.mp4"))
        assertTrue(ModuleSource.malformed("nuvx://watch?v=rpemDBaFK0c"))
        assertTrue(ModuleSource.malformed(""))
        assertTrue(ModuleSource.malformed("undefined"))
        assertTrue(ModuleSource.malformed("null"))
    }

    @Test
    fun `refuses to let a module point the player at anything but http`() {
        assertTrue(ModuleSource.malformed("file:///data/data/com.bxane.nuvx/files/x.flac"))
        assertTrue(ModuleSource.malformed("content://media/external/audio/media/42"))
        assertTrue(ModuleSource.malformed("ftp://cdn.example.com/f.mp3"))
    }

    @Test
    fun `accepts an origin with no path of its own`() {
        assertFalse(ModuleSource.malformed("https://sp-ad-fa.audio.tidal.com"))
    }

    @Test
    fun `reads a tier out of whatever a module calls it`() {
        assertEquals("LOSSLESS", ModuleSource.qualityTier("LOSSLESS"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("FLAC 16-bit / 44.1kHz"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("hires-96"))
        assertEquals("HIGH", ModuleSource.qualityTier("HIGH"))
        assertEquals("HIGH", ModuleSource.qualityTier("320kbps"))
        assertEquals("LOW", ModuleSource.qualityTier("128kbps"))
        assertEquals("LOW", ModuleSource.qualityTier("LOW"))
        assertNull(ModuleSource.qualityTier(""))
        assertNull(ModuleSource.qualityTier("Deadbeat"))
    }

    @Test
    fun `does not mistake a bit depth for a bitrate tier`() {
        assertEquals("LOSSLESS", ModuleSource.qualityTier("24-bit / 192 kHz"))
        assertEquals("LOSSLESS", ModuleSource.qualityTier("FLAC 128"))
    }

    @Test
    fun `orders tiers worst to best`() {
        assertEquals(listOf("LOW", "HIGH", "LOSSLESS"), ModuleSource.TIERS)
    }

    @Test
    fun `reads a runtime off a queue row`() {
        assertEquals(225, TrackMatcher.secondsOf("3:45"))
        assertEquals(3723, TrackMatcher.secondsOf("1:02:03"))
        assertNull(TrackMatcher.secondsOf(null))
        assertNull(TrackMatcher.secondsOf("live"))
        assertNull(TrackMatcher.secondsOf("0:00"))
    }
}
