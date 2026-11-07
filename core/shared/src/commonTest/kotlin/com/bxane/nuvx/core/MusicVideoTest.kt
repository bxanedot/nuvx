package com.bxane.nuvx.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MusicVideoTest {
    @Test fun matcherRejectsTitlesWithoutSongIdentity() {
        for (title in listOf("", "   ", "!!!", "(Official Music Video)")) {
            val song = Track("song-id", title, listOf("Nova"), durationMs = 240_000)
            val candidate = Track("video123456", "(Official Music Video)", listOf("Nova"),
                durationMs = 240_000, mediaKind = MediaKind.VIDEO)
            assertNull(MusicVideoMatcher.bestMatch(song, listOf(candidate)), "No song title: '$title'")
        }
    }
    @Test fun youtubeFallbackRequiresAVideoAndUsesItsPairedVideoId() {
        val directVideo = Track("abcdefghijk", "Signal", mediaKind = MediaKind.VIDEO)
        val pairedVideo = Track("songid12345", "Signal", mediaKind = MediaKind.VIDEO, pairedVideoId = "lmnopqrstuv")
        val song = Track("abcdefghijk", "Signal")
        val malformedVideo = Track("bad id", "Signal", mediaKind = MediaKind.VIDEO)

        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", MusicVideoMatcher.youtubeWatchUrl(directVideo))
        assertEquals("https://www.youtube.com/watch?v=lmnopqrstuv", MusicVideoMatcher.youtubeWatchUrl(pairedVideo))
        assertNull(MusicVideoMatcher.youtubeWatchUrl(song))
        assertNull(MusicVideoMatcher.youtubeWatchUrl(malformedVideo))
    }

    @Test fun playbackFailureMessageExplainsVideoFallback() {
        val video = Track("video-id", "Signal", listOf("Nova"), mediaKind = MediaKind.VIDEO)
        val song = Track("song-id", "Signal", listOf("Nova"))

        assertEquals("Video playback failed. Try again later or switch to song audio.", video.playbackFailureMessage())
        assertEquals("Playback failed. Retry or choose another engine in Settings.", song.playbackFailureMessage())
    }

    @Test fun videoSearchResultsAreMarkedAsVideoItems() {
        val body = """{"items":[{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"abcdefghijk"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Signal (Official Music Video)"}]}}}]
        }}]}"""

        val result = YtmJson.parseSearch(body, videoResults = true)

        assertEquals(MediaKind.VIDEO, result.tracks.single().mediaKind)
        assertEquals("abcdefghijk", result.tracks.single().playbackVideoId)
    }

    @Test fun videoSearchRetainsArtistBrowseIdentityForMatching() {
        val body = """{"items":[{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"abcdefghijk"},
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Signal (Official Music Video)"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Nova","navigationEndpoint":{"browseEndpoint":{"browseId":"UCnova","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}}}
            ]
        }}]}"""

        val result = YtmJson.parseSearch(body, videoResults = true)

        assertEquals(listOf("Nova"), result.tracks.single().artists)
        assertEquals("UCnova", result.tracks.single().artistId)
    }

    @Test fun artistVideoShelfCarriesItsCategoryAndDuration() {
        val body = """{"contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Live & Performances"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Signal (Live)"}]},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},
                "subtitle":{"runs":[{"text":"Nova"}]},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"thumb"}]}}},
                "thumbnailOverlay":{"musicThumbnailOverlayRenderer":{"content":{"musicThumbnailOverlayTimeStatusRenderer":{"text":{"runs":[{"text":"4:12"}]}}}}}
            }}]
        }}]}"""

        val artist = YtmJson.parseArtist("UC1", body)

        assertEquals(ArtistVideoCategory.LIVE_PERFORMANCE, artist.videoShelves.single().category)
        assertEquals("abcdefghijk", artist.videoShelves.single().items.single().videoId)
        assertEquals(MediaKind.VIDEO, artist.videoShelves.single().items.single().mediaKind)
        assertEquals(252_000L, artist.videoShelves.single().items.single().durationMs)
        assertEquals("thumb", artist.videoShelves.single().items.single().artworkUrl)
    }

    @Test fun genericVideoShelfIsNotMisclassifiedAsMusicVideos() {
        val body = """{"contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Videos"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Signal"}]},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}
            }}]
        }}]}"""

        val artist = YtmJson.parseArtist("UC1", body)

        assertEquals(ArtistVideoCategory.OTHER, artist.videoShelves.single().category)
    }

    @Test fun artistVideoCategoryMatchingUsesWholeWords() {
        val body = """{"contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Deliverables"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Signal"}]},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}
            }}]
        }}]}"""

        val artist = YtmJson.parseArtist("UC1", body)

        assertEquals(ArtistVideoCategory.OTHER, artist.videoShelves.single().category)
    }

    @Test fun artistVideoShelvesCanBeFilteredWithoutChangingTheirOrder() {
        val music = ArtistVideoShelf("Music videos", ArtistVideoCategory.MUSIC_VIDEO, listOf(Track("music", "Signal", listOf("Nova"))))
        val live = ArtistVideoShelf("Live", ArtistVideoCategory.LIVE_PERFORMANCE, listOf(Track("live", "Signal", listOf("Nova"))))
        val lyrics = ArtistVideoShelf("Lyrics", ArtistVideoCategory.LYRIC_VIDEO, listOf(Track("lyrics", "Signal", listOf("Nova"))))
        val shelves = listOf(music, live, lyrics)

        assertEquals(shelves, filterArtistVideoShelves(shelves, null))
        assertEquals(listOf(live), filterArtistVideoShelves(shelves, ArtistVideoCategory.LIVE_PERFORMANCE))
    }

    @Test fun matcherRequiresTitleArtistDurationAndSameRecordingVersion() {
        val song = Track("song-id", "Midnight Signal", listOf("Nova"), durationMs = 240_000)
        val live = Track("live-id", "Midnight Signal (Live)", listOf("Nova"), durationMs = 240_000, mediaKind = MediaKind.VIDEO)
        val otherArtist = Track("other-id", "Midnight Signal (Official Video)", listOf("Elsewhere"), durationMs = 241_000, mediaKind = MediaKind.VIDEO)
        val longCut = Track("long-id", "Midnight Signal (Official Music Video)", listOf("Nova"), durationMs = 520_000, mediaKind = MediaKind.VIDEO)
        val match = Track("lmnopqrstuv", "Midnight Signal (Official Music Video)", listOf("Nova"), durationMs = 244_000, mediaKind = MediaKind.VIDEO)

        assertEquals(match, MusicVideoMatcher.bestMatch(song, listOf(live, otherArtist, longCut, match)))
        assertNull(MusicVideoMatcher.bestMatch(song, listOf(live, otherArtist, longCut)))
    }

    @Test fun matcherPrefersExplicitMusicVideoOverLyricVideoEvenWhenLyricResultComesFirst() {
        val song = Track("song-id", "Midnight Signal", listOf("Nova"), durationMs = 240_000)
        val lyric = Track("lyric123456", "Midnight Signal (Lyric Video)", listOf("Nova"), durationMs = 240_000, mediaKind = MediaKind.VIDEO)
        val music = Track("music123456", "Midnight Signal (Official Music Video)", listOf("Nova"), durationMs = 246_000, mediaKind = MediaKind.VIDEO)

        assertEquals(music, MusicVideoMatcher.bestMatch(song, listOf(lyric, music)))
        assertEquals(lyric, MusicVideoMatcher.bestMatch(song, listOf(lyric)))
    }

    @Test fun matcherDoesNotPairOriginalWithRemixVideo() {
        val original = Track("song-id", "Midnight Signal", listOf("Nova"), durationMs = 240_000)
        val remix = Track("remix-id", "Midnight Signal (Travis Barker Remix)", listOf("Nova"), durationMs = 240_000)
        val originalVideo = Track("original123", "Midnight Signal (Official Music Video)", listOf("Nova"),
            durationMs = 240_000, mediaKind = MediaKind.VIDEO)
        val remixVideo = Track("remixvideo1", "Midnight Signal (Travis Barker Remix) (Official Music Video)", listOf("Nova"),
            durationMs = 240_000, mediaKind = MediaKind.VIDEO)

        assertEquals(originalVideo, MusicVideoMatcher.bestMatch(original, listOf(remixVideo, originalVideo)))
        assertNull(MusicVideoMatcher.bestMatch(original, listOf(remixVideo)))
        assertEquals(remixVideo, MusicVideoMatcher.bestMatch(remix, listOf(remixVideo)))
    }

    @Test fun matcherRejectsConflictingArtistIdsDespiteMatchingNames() {
        val song = Track("song-id", "Midnight Signal", listOf("Nova"), durationMs = 240_000, artistId = "UCnova")
        val video = Track("video123456", "Midnight Signal (Official Music Video)", listOf("Nova"),
            durationMs = 240_000, artistId = "UCother", mediaKind = MediaKind.VIDEO)

        assertNull(MusicVideoMatcher.bestMatch(song, listOf(video)))
    }

    @Test fun matcherUsesMatchingArtistIdWhenVideoBylineOmitsArtistName() {
        val song = Track("song-id", "Midnight Signal", listOf("Nova"), durationMs = 240_000, artistId = "UCnova")
        val video = Track("video123456", "Midnight Signal (Official Music Video)", durationMs = 240_000,
            artistId = "UCnova", mediaKind = MediaKind.VIDEO)

        assertEquals(video, MusicVideoMatcher.bestMatch(song, listOf(video)))
    }

    @Test fun videoSearchUsesPrimaryAndPlaybackFallsBackFromUnsupportedEngine() = runTest {
        val audioUrl = "https://r1.googlevideo.com/audio?sig=secret-audio"
        val videoUrl = "https://r1.googlevideo.com/video?sig=secret-video"
        val headers = mapOf("Authorization" to "secret-header")
        var resolved = VideoStream("abcdefghijk", audioUrl, videoUrl,
            headers, 720, 1280, "video/mp4", "audio/mp4", "avc1", listOf(360, 720), BackendId.INNERTUBEX)
        var primarySearches = 0
        var alternativeSearches = 0
        val primary = object : MusicBackend, VideoCapableBackend {
            override val id = BackendId.INNERTUBEX
            override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                primarySearches++
                assertEquals(SearchFilter.VIDEOS, filter)
                return SearchResults(emptyList(), emptyList())
            }
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String): AudioStream? = null
            override suspend fun video(videoId: String, maxHeight: Int?) = resolved
        }
        val alternative = object : MusicBackend {
            override val id = BackendId.EXCOR
            override suspend fun search(query: String, filter: SearchFilter): SearchResults {
                alternativeSearches++
                return SearchResults(emptyList(), emptyList())
            }
            override suspend fun radio(seedVideoId: String, continuation: String?) = RadioPage(emptyList(), null)
            override suspend fun stream(videoId: String): AudioStream? = null
        }
        val router = BackendRouter(mapOf(primary.id to primary, alternative.id to alternative), selected = BackendId.EXCOR)

        router.searchVideos("Signal")
        assertEquals(1, primarySearches)
        assertEquals(0, alternativeSearches)
        assertEquals(resolved, router.resolveVideo("abcdefghijk"))
        assertEquals(BackendId.INNERTUBEX, router.resolveVideo("abcdefghijk").backend)
        assertFalse(resolved.toString().contains("secret"))

        resolved = resolved.copy(videoUrl = "http://127.0.0.1/private")
        assertFailsWith<BackendFailure> { router.resolveVideo("abcdefghijk") }
        resolved = resolved.copy(audioUrl = "https://googlevideo.com.evil.test/private")
        assertFailsWith<BackendFailure> { router.resolveVideo("abcdefghijk") }

        val unsupported = BackendRouter(mapOf(alternative.id to alternative), selected = BackendId.EXCOR)
        assertFailsWith<BackendFailure> { unsupported.resolveVideo("abcdefghijk") }
    }
}
