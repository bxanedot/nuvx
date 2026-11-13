package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.model.ArtistSubscriptionState
import com.bxane.nuvx.data.model.BrowseItem
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SongMenu
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull







object InnertubeParser {

    fun parseSearchSongs(response: JsonObject): List<Song> =
        parseSearch(response).filterIsInstance<SearchResult.Track>().map { it.song }














    fun parseSearchPage(
        response: JsonObject,
        includeVideos: Boolean = false,
    ): List<SearchResult> {
        val topResults: List<SearchResult> = if (includeVideos) emptyList() else {
            collectRenderers(response, "musicCardShelfRenderer").mapNotNull { card ->
                parseCardShelfSong(card)?.let { SearchResult.TopTrack(it) }
                    ?: parseCardShelfBrowse(card)?.let { SearchResult.Browse(it) }
            }
        }
        val rows = collectRenderers(response, "musicResponsiveListItemRenderer")
        val cardCredits: List<Pair<JsonObject, Credits>> = if (includeVideos) emptyList() else {
            collectRenderers(response, "musicCardShelfRenderer").flatMap { card ->
                val credit = cardShelfCredit(card) ?: return@flatMap emptyList()
                collectRenderers(card, "musicResponsiveListItemRenderer").map { it to credit }
            }
        }
        val seen = HashSet<String>()
        return buildList {
            topResults.forEach { result ->
                when (result) {
                    is SearchResult.TopTrack ->
                        if (!result.song.isVideo && seen.add("v:${result.song.videoId}")) {
                            add(result)
                        }
                    is SearchResult.Browse ->
                        if (seen.add("b:${result.item.browseId}")) add(result)
                    is SearchResult.Track -> Unit
                }
            }
            rows.forEach { renderer ->
                val browse = parseBrowseItem(renderer)
                if (browse != null) {
                    if (seen.add("b:${browse.browseId}")) add(SearchResult.Browse(browse))
                } else {
                    val fallback = cardCredits.firstOrNull { it.first === renderer }?.second
                    parseResponsiveListItem(renderer, fallback ?: Credits())?.let { song ->
                        if (song.isVideo && !AppSettings.convertVideoToAudio.value) return@let
                        if (song.isVideo == includeVideos && seen.add("v:${song.videoId}")) {
                            add(SearchResult.Track(song))
                        }
                    }
                }
            }
        }
    }

    fun parseSearch(response: JsonObject): List<SearchResult> =
        parseSearchPage(response)

    fun parseSearchSuggestions(response: JsonObject): List<String> =
        collectRenderers(response, "searchSuggestionRenderer")
            .mapNotNull { renderer ->
                val query = renderer.o("navigationEndpoint").o("searchEndpoint").s("query")
                    ?: renderer.o("suggestion").runs()
                query.takeIf { it.isNotBlank() }
            }
            .distinct()


    private fun collectRenderers(root: JsonElement, name: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node[name] as? JsonObject)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun parseBrowseItem(renderer: JsonObject): BrowseItem? {
        val endpoint = renderer.o("navigationEndpoint").o("browseEndpoint") ?: return null
        val browseId = endpoint.s("browseId") ?: return null
        val pageType = endpoint.o("browseEndpointContextSupportedConfigs")
            .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()

        val columns = renderer.a("flexColumns").orEmpty()
        val title = columns.getOrNull(0)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if (title.isBlank()) return null
        val subtitle = columns.getOrNull(1)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if (VIDEO_WORD.containsMatchIn(title) || VIDEO_WORD.containsMatchIn(subtitle)) return null

        return BrowseItem(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = renderer.o("thumbnail").o("musicThumbnailRenderer")
                .o("thumbnail").a("thumbnails").best(),
            type = when {
                "ALBUM" in pageType -> BrowseType.ALBUM
                "ARTIST" in pageType -> BrowseType.ARTIST
                "PLAYLIST" in pageType -> BrowseType.PLAYLIST
                else -> BrowseType.OTHER
            },
        )
    }





    private fun parseResponsiveListItem(
        renderer: JsonObject?,
        fallback: Credits = Credits(),
    ): Song? {
        if (renderer == null) return null
        val videoId = renderer.o("playlistItemData").s("videoId")
            ?: renderer.o("overlay")
                .o("musicItemThumbnailOverlayRenderer").o("content")
                .o("musicPlayButtonRenderer").o("playNavigationEndpoint")
                .o("watchEndpoint").s("videoId")
            ?: return null

        val columns = renderer.a("flexColumns").orEmpty()
        val title = columns.getOrNull(0)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if (title.isBlank()) return null

        val subtitleRuns = columns.getOrNull(1)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").a("runs").orEmpty()
        val subtitle = subtitleRuns.joinToString("") { it.s("text").orEmpty() }
        val parts = subtitle.split(" • ").filter { it.isNotBlank() }
        val duration = parts.lastOrNull()?.takeIf { it.matches(DURATION) }
            ?: renderer.a("fixedColumns").orEmpty().firstNotNullOfOrNull { column ->
                column.o("musicResponsiveListItemFixedColumnRenderer")
                    .o("text").runs().takeIf { it.matches(DURATION) }
            }
        val rowType = parts.firstOrNull { it.lowercase() in TYPE_WORDS }?.lowercase()


        val artist = parts.firstOrNull {
            !it.matches(DURATION) && it.lowercase() !in TYPE_WORDS && !it.matches(TALLY)
        }



        val credits = creditsOf(
            columns.flatMap {
                it.o("musicResponsiveListItemFlexColumnRenderer").o("text").a("runs").orEmpty()
            },
        )
        val creditedArtists = artistNamesFromRuns(subtitleRuns)

        val thumbnails = renderer.o("thumbnail").o("musicThumbnailRenderer")
            .o("thumbnail").a("thumbnails")

        return Song(
            videoId = videoId,
            title = title,




            artist = creditedArtists
                ?: credits.artistName?.takeIf { it.isNotBlank() }
                ?: artist
                ?: fallback.artistName
                ?: "Unknown artist",
            thumbnailUrl = thumbnails.best(),
            durationText = duration,
            artistId = credits.artistId ?: fallback.artistId,
            albumId = credits.albumId ?: fallback.albumId,
            albumName = credits.albumName ?: fallback.albumName,

            setVideoId = renderer.o("playlistItemData").s("playlistSetVideoId"),


            isVideo = rowType == "video" || thumbnails.isNotSquare(),
        )
    }


    internal data class Credits(
        val artistId: String? = null,
        val artistName: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
    )

    private fun creditsOf(runs: List<JsonElement>): Credits {
        var credits = Credits()
        runs.forEach { run ->
            val browse = run.o("navigationEndpoint").o("browseEndpoint")
            val id = browse.s("browseId") ?: return@forEach
            val pageType = browse.o("browseEndpointContextSupportedConfigs")
                .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
            credits = when {
                "ARTIST" in pageType && credits.artistId == null ->
                    credits.copy(artistId = id, artistName = run.s("text"))
                "ALBUM" in pageType && credits.albumId == null ->
                    credits.copy(albumId = id, albumName = run.s("text"))
                else -> credits
            }
        }
        return credits
    }









    private fun pageCredit(root: JsonElement): Credits {
        val header = HEADER_RENDERERS.firstNotNullOfOrNull {
            collectRenderers(root, it).firstOrNull()
        } ?: return Credits()



        val lines = HEADER_CREDIT_LINES.map { header.o(it).a("runs").orEmpty() }
        val parts = lines.flatMap { line ->
            line.joinToString("") { it.s("text").orEmpty() }.split(" • ").map(String::trim)
        }
        if (parts.none { it.lowercase() in RELEASE_WORDS }) return Credits()

        val credits = creditsOf(lines.flatten())
        if (credits.artistName?.isNotBlank() == true) return credits


        val name = parts.firstOrNull {
            it.isNotBlank() && it.lowercase() !in TYPE_WORDS && !it.matches(TALLY) &&
                !it.matches(YEAR) && !it.matches(DURATION)
        }
        return credits.copy(artistName = name)
    }







    fun parseHome(response: JsonObject): List<HomeShelf> {
        val sections = homeSectionContents(response)
        val fromColumn = sections.mapNotNull { section ->
            section.o("musicCarouselShelfRenderer")?.let(::carouselShelf)
                ?: section.o("musicShelfRenderer")?.let(::plainShelf)
        }
        if (fromColumn.isNotEmpty()) return fromColumn


        return parseHomeContinuation(response)
    }






    fun podcastsChipParams(home: JsonObject): String? {
        val chips = home.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").o("header")
            .o("chipCloudRenderer").a("chips").orEmpty()
        return chips.firstNotNullOfOrNull { element ->
            val chip = element.o("chipCloudChipRenderer") ?: return@firstNotNullOfOrNull null
            val params = chip.o("navigationEndpoint").o("browseEndpoint").s("params")
                ?: return@firstNotNullOfOrNull null
            val title = chip.o("text").runs()
            params.takeIf {
                title.equals("Podcasts", ignoreCase = true) || PODCASTS_CHIP in it
            }
        }
    }

    private fun homeSectionContents(response: JsonObject): List<JsonElement> {
        val contents = response.o("contents")
        contents.o("singleColumnBrowseResultsRenderer")
            .a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val two = contents.o("twoColumnBrowseResultsRenderer")
        sequenceOf(
            two.o("primaryContents").o("sectionListRenderer").a("contents"),
            two.o("secondaryContents").o("sectionListRenderer").a("contents"),
            two.a("tabs")?.firstOrNull()
                .o("tabRenderer").o("content").o("sectionListRenderer").a("contents"),
        ).firstOrNull { !it.isNullOrEmpty() }?.let { return it }
        return emptyList()
    }





    fun parseHomeContinuation(root: JsonElement): List<HomeShelf> {
        val out = mutableListOf<HomeShelf>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["musicCarouselShelfRenderer"] as? JsonObject)
                        ?.let(::carouselShelf)?.let(out::add)
                    (node["musicShelfRenderer"] as? JsonObject)
                        ?.let(::plainShelf)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }













    fun parseMoodAndGenres(response: JsonObject): List<MoodGenreSection> {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()
        val parsed = sections.mapNotNull { section ->
            val grid = section.o("gridRenderer") ?: return@mapNotNull null
            val title = grid.o("header").o("gridHeaderRenderer").o("title").runs()
            val items = grid.a("items").orEmpty().mapNotNull { item ->
                val button = item.o("musicNavigationButtonRenderer") ?: return@mapNotNull null




                val endpoint = button.o("clickCommand").o("browseEndpoint")
                    ?: button.o("navigationEndpoint").o("browseEndpoint")
                    ?: return@mapNotNull null
                val browseId = endpoint.s("browseId") ?: return@mapNotNull null
                val label = button.o("buttonText").runs().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                MoodGenre(
                    title = label,
                    browseId = browseId,
                    params = endpoint.s("params"),
                    stripeColor = button.o("solid").s("leftStripeColor")?.toLongOrNull(),
                )
            }
            if (title.isBlank() || items.isEmpty()) null else MoodGenreSection(title, items)
        }





        return parsed.filterIndexed { index, section ->
            val later = parsed.drop(index + 1)
                .flatMapTo(HashSet()) { it.items.map { item -> item.browseId to item.params } }
            val shortcuts = section.items.any { (it.browseId to it.params) in later }
            !shortcuts && !section.title.equals("For you", ignoreCase = true)
        }
    }










    fun continuationToken(root: JsonElement): String? {
        val endpoint = collectRenderers(root, "continuationItemRenderer").firstOrNull()
            .o("continuationEndpoint")
        (endpoint.o("continuationCommand")
            ?: endpoint.o("commandExecutorCommand").a("commands")
                ?.firstNotNullOfOrNull { it.o("continuationCommand") })
            .s("token")
            ?.let { return it }
        return collectRenderers(root, "nextContinuationData").firstOrNull().s("continuation")
    }

    private fun carouselShelf(carousel: JsonObject): HomeShelf? {
        val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
        val title = header.o("title").runs()
        val strapline = header.o("strapline").runs()
        val parsed = carousel.a("contents").orEmpty().mapNotNull { item ->
            parseTwoRowItem(item.o("musicTwoRowItemRenderer"))
                ?: parseResponsiveListItem(item.o("musicResponsiveListItemRenderer"))
                    ?.let { song ->
                        ShelfItem(
                            song.title, song.artist, song.thumbnailUrl, song.videoId, null,
                            isVideo = song.isVideo,
                        )
                    }



                ?: parseArtistRow(item.o("musicResponsiveListItemRenderer"))
        }


        val items = if (VIDEO_WORD.containsMatchIn(title)) parsed.filter { it.videoId != null } else parsed
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items, strapline)
    }

    private fun plainShelf(shelf: JsonObject): HomeShelf? {
        val title = shelf.o("title").runs()
        val items = shelf.a("contents").orEmpty().mapNotNull {
            parseResponsiveListItem(it.o("musicResponsiveListItemRenderer"))
        }.map {
            ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, isVideo = it.isVideo)
        }
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items)
    }





    fun parseLibraryItems(root: JsonElement): List<ShelfItem> {
        val out = LinkedHashMap<String, ShelfItem>()
        collectRenderers(root, "musicTwoRowItemRenderer").forEach { renderer ->
            val item = parseTwoRowItem(renderer) ?: return@forEach
            item.browseId?.let { id -> out.getOrPut(id) { item } }
        }
        collectRenderers(root, "musicResponsiveListItemRenderer").forEach { renderer ->
            val item = parseBrowseItem(renderer) ?: return@forEach
            out.getOrPut(item.browseId) {
                ShelfItem(item.title, item.subtitle, item.thumbnailUrl, null, item.browseId)
            }
        }
        return out.values.toList()
    }






    private fun parseArtistRow(renderer: JsonObject?): ShelfItem? {
        if (renderer == null) return null
        val endpoint = renderer.o("navigationEndpoint").o("browseEndpoint")
        val pageType = endpoint.o("browseEndpointContextSupportedConfigs")
            .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
        if ("ARTIST" !in pageType) return null
        val browseId = endpoint.s("browseId") ?: return null

        val columns = renderer.a("flexColumns").orEmpty()
        val title = columns.getOrNull(0)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if (title.isBlank()) return null
        val subtitle = columns.getOrNull(1)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()

        val thumbnails = renderer.o("thumbnail").o("musicThumbnailRenderer")
            .o("thumbnail").a("thumbnails")
        return ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnails.best(),
            videoId = null,
            browseId = browseId,
        )
    }






    private fun parseTwoRowItem(renderer: JsonObject?): ShelfItem? {
        if (renderer == null) return null
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null
        val endpoint = renderer.o("navigationEndpoint")
        val browseId = endpoint.o("browseEndpoint").s("browseId")




        val videoId = endpoint.o("watchEndpoint").s("videoId")
            ?: browseId?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
        val resolvedBrowseId = browseId?.takeUnless { it.startsWith("MPED") }
        val thumbnails = renderer.o("thumbnailRenderer").o("musicThumbnailRenderer")
            .o("thumbnail").a("thumbnails")
        val subtitle = renderer.o("subtitle").runs()


        val isVideo = resolvedBrowseId == null && videoId != null && (
            thumbnails.isNotSquare() || renderer.s("aspectRatio")?.contains("16_9") == true
        )



        if (resolvedBrowseId != null &&
            (VIDEO_WORD.containsMatchIn(title) || VIDEO_WORD.containsMatchIn(subtitle))
        ) {
            return null
        }
        return ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnails.best(),
            videoId = videoId,
            browseId = resolvedBrowseId,
            isVideo = isVideo,
        )
    }





    data class ArtistPage(
        val songs: List<Song>,
        val sections: List<HomeShelf>,
        val thumbnailUrl: String?,
        val name: String?,
        val description: String?,
        val subscriberCountText: String?,
        val monthlyListenerCount: String?,
        val subscription: ArtistSubscriptionState?,
    )






    fun parseArtistPage(response: JsonObject): ArtistPage {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        val songs = mutableListOf<Song>()
        val shelves = mutableListOf<HomeShelf>()
        val header = response["header"]


        val credit = Credits(artistName = artistName(header))

        sections.forEach { section ->
            section.o("musicShelfRenderer")?.let { shelf ->
                shelf.a("contents").orEmpty().forEach { row ->
                    parseResponsiveListItem(row.o("musicResponsiveListItemRenderer"), credit)
                        ?.let(songs::add)
                }
            }
            section.o("musicCarouselShelfRenderer")?.let { carousel ->
                val carouselHeader = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
                val title = carouselHeader.o("title").runs()
                if (VIDEO_WORD.containsMatchIn(title)) return@let
                val items = carousel.a("contents").orEmpty().mapNotNull {
                    parseTwoRowItem(it.o("musicTwoRowItemRenderer"))
                }.filter { it.browseId != null }
                if (title.isNotBlank() && items.isNotEmpty()) {
                    shelves += HomeShelf(title, items)
                }
            }
        }
        return ArtistPage(
            songs = songs,
            sections = shelves,
            thumbnailUrl = artistThumbnail(header),
            name = credit.artistName,
            description = parseDescription(response),
            subscriberCountText = subscriberCount(header),
            monthlyListenerCount = monthlyListeners(header),
            subscription = subscription(header),
        )
    }


    private fun subscription(header: JsonElement?): ArtistSubscriptionState? {
        val immersive = header.o("musicImmersiveHeaderRenderer") ?: return null
        val buttons = listOfNotNull(
            immersive.o("subscriptionButton2").o("subscribeButtonRenderer"),
            immersive.o("subscriptionButton").o("subscribeButtonRenderer"),
        )
        return buttons.firstNotNullOfOrNull { button ->
            val subscribed = (button["subscribed"] as? JsonPrimitive)?.booleanOrNull
                ?: return@firstNotNullOfOrNull null
            val channelId = button.s("channelId")
                ?: button.a("serviceEndpoints")?.firstNotNullOfOrNull { endpoint ->
                    endpoint.o("subscribeEndpoint").a("channelIds")
                        ?.firstOrNull().let { (it as? JsonPrimitive)?.contentOrNull }
                }
                ?: return@firstNotNullOfOrNull null
            ArtistSubscriptionState(channelId = channelId, subscribed = subscribed)
        }
    }






    private fun subscriberCount(header: JsonElement?): String? {
        val immersive = header.o("musicImmersiveHeaderRenderer") ?: return null
        val button2 = immersive.o("subscriptionButton2").o("subscribeButtonRenderer")
        val button1 = immersive.o("subscriptionButton").o("subscribeButtonRenderer")
        return button2.o("subscriberCountWithSubscribeText").firstRunText()
            ?: button1.o("longSubscriberCountText").firstRunText()
            ?: button1.o("shortSubscriberCountText").firstRunText()
    }


    private fun monthlyListeners(header: JsonElement?): String? =
        header.o("musicImmersiveHeaderRenderer").o("monthlyListenerCount").firstRunText()


    private fun artistName(header: JsonElement?): String? {
        val renderer = header.o("musicImmersiveHeaderRenderer")
            ?: header.o("musicVisualHeaderRenderer")
            ?: return null
        return renderer.o("title").runs().takeIf { it.isNotBlank() }
    }






    private fun artistThumbnail(header: JsonElement?): String? {
        if (header == null) return null
        val immersive = header.o("musicImmersiveHeaderRenderer")
        val visual = header.o("musicVisualHeaderRenderer")
        val renderer = (
            immersive.o("thumbnail")
                ?: visual.o("foregroundThumbnail")
                ?: visual.o("thumbnail")
            ).o("musicThumbnailRenderer")


            ?: collectRenderers(header, "musicThumbnailRenderer").firstOrNull()
        return renderer.o("thumbnail").a("thumbnails").best()
    }






    fun collectSongsDeep(root: JsonElement): List<Song> {
        val out = LinkedHashMap<String, Song>()

        val pageCredit = pageCredit(root)
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["musicResponsiveListItemRenderer"]?.let { renderer ->
                        parseResponsiveListItem(renderer as? JsonObject, pageCredit)
                            ?.let { out[it.videoId] = it }
                    }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out.values.toList()
    }


    data class PlaylistShelfPage(
        val songs: List<Song>,
        val continuation: String?,
        val suggested: List<Song> = emptyList(),
    )









    fun parsePlaylistShelf(root: JsonElement): PlaylistShelfPage? {
        val scope: JsonElement = root.o("continuationContents")
            ?: root.o("contents").o("twoColumnBrowseResultsRenderer").o("secondaryContents")
            ?: return null
        val looksLikePlaylist = collectRenderers(scope, "musicPlaylistShelfRenderer").isNotEmpty() ||
            scope.o("musicPlaylistShelfContinuation") != null
        if (!looksLikePlaylist) return null

        val pageCredit = pageCredit(root)
        val parsed = collectRenderers(scope, "musicResponsiveListItemRenderer")
            .mapNotNull { parseResponsiveListItem(it, pageCredit) }
            .distinctBy { it.videoId }
        val songs = parsed.filter { it.setVideoId != null }
        val suggested = parsed.filter { it.setVideoId == null }
        val token = continuationToken(scope)
        return PlaylistShelfPage(songs, token, suggested)
    }


    data class BrowseHeader(
        val title: String,

        val subtitle: String,
        val thumbnailUrl: String?,
    )


    fun parseBrowseHeader(root: JsonElement): BrowseHeader? {
        val header = HEADER_RENDERERS.firstNotNullOfOrNull {
            collectRenderers(root, it).firstOrNull()
        } ?: return null
        val title = header.o("title").runs()
        if (title.isBlank()) return null
        val subtitle = header.o("straplineTextOne").runs()
            .ifBlank { header.o("subtitle").runs() }
        return BrowseHeader(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = collectRenderers(header, "musicThumbnailRenderer").firstOrNull()
                .o("thumbnail").a("thumbnails").best(),
        )
    }







    fun parseDescription(root: JsonElement): String? {
        val shelf = collectRenderers(root, "musicDescriptionShelfRenderer")
            .firstOrNull()?.o("description").runs()
        if (shelf.isNotBlank()) return shelf
        return (HEADER_RENDERERS + "musicImmersiveHeaderRenderer")
            .firstNotNullOfOrNull { name ->
                collectRenderers(root, name).firstOrNull()
                    ?.o("description").runs().takeIf { it.isNotBlank() }
            }
    }




    fun parseWatchQueue(root: JsonElement): List<Song> {
        val out = LinkedHashMap<String, Song>()
        collectRenderers(root, "playlistPanelVideoRenderer").forEach { renderer ->
            val videoId = renderer.s("videoId") ?: return@forEach
            val title = renderer.o("title").runs()
            if (title.isBlank()) return@forEach


            val bylineRuns = renderer.o("longBylineText").a("runs").orEmpty()
            val byline = bylineRuns.map { it.s("text").orEmpty() }
            val artist = byline.takeWhile { !it.contains("•") }.joinToString("").trim()
            val credits = creditsOf(bylineRuns)
            out[videoId] = Song(
                videoId = videoId,
                title = title,
                artist = artist.ifBlank { "Unknown artist" },
                thumbnailUrl = renderer.o("thumbnail").a("thumbnails").best(),
                durationText = renderer.o("lengthText").runs().takeIf { it.isNotBlank() },
                artistId = credits.artistId,
                albumId = credits.albumId,
                albumName = credits.albumName,


                isVideo = byline.any { it.contains("views", ignoreCase = true) },
            )
        }
        return out.values.toList()
    }








    fun parseAccount(response: JsonElement): Account? {
        val header = collectRenderers(response, "activeAccountHeaderRenderer").firstOrNull()
            ?: return null
        val name = header.o("accountName").runs()
        if (name.isBlank()) return null
        val email = header.o("email").runs()
            .ifBlank { header.o("email").s("simpleText").orEmpty() }
            .ifBlank { header.o("channelHandle").runs() }
        return Account(
            name = name,
            email = email,
            photoUrl = header.o("accountPhoto").a("thumbnails").best(),
        )
    }

    fun parseSongMenu(root: JsonElement, videoId: String): SongMenu? {
        val row = collectRenderers(root, "playlistPanelVideoRenderer")
            .firstOrNull { it.s("videoId") == videoId }
            ?: return null
        val likeStatus = when (
            collectRenderers(row, "likeButtonRenderer").firstOrNull().s("likeStatus")
        ) {
            "LIKE" -> LikeStatus.LIKE
            "DISLIKE" -> LikeStatus.DISLIKE
            "INDIFFERENT" -> LikeStatus.INDIFFERENT
            else -> null
        }
        val toggle = collectRenderers(row, "toggleMenuServiceItemRenderer")
            .firstOrNull { it.feedbackToken("defaultServiceEndpoint") != null && it.isLibraryToggle }
        val defaultAdds = toggle.o("defaultIcon").s("iconType") == "LIBRARY_ADD"
        val defaultToken = toggle.feedbackToken("defaultServiceEndpoint")
        val toggledToken = toggle.feedbackToken("toggledServiceEndpoint")
        return SongMenu(
            likeStatus = likeStatus?.name,
            inLibrary = toggle != null && !defaultAdds,
            addToLibraryToken = if (defaultAdds) defaultToken else toggledToken,
            removeFromLibraryToken = if (defaultAdds) toggledToken else defaultToken,
        )
    }

    private fun JsonElement?.feedbackToken(endpoint: String): String? =
        this.o(endpoint).o("feedbackEndpoint").s("feedbackToken")

    private val JsonElement?.isLibraryToggle: Boolean
        get() = LIBRARY_ICONS.any {
            o("defaultIcon").s("iconType") == it || o("toggledIcon").s("iconType") == it
        }

    private val LIBRARY_ICONS = setOf("LIBRARY_ADD", "LIBRARY_REMOVE", "LIBRARY_SAVED")

    fun parseLibraryState(root: JsonElement): LibraryState? {
        val buttons = collectRenderers(root, "musicResponsiveHeaderRenderer")
            .firstOrNull()
            .a("buttons")
            .orEmpty()
        val save = buttons.firstNotNullOfOrNull {
            it.o("toggleButtonRenderer")?.takeIf { b -> b.isSaveToggle }
        } ?: return null
        if (save.s("isDisabled") == "true") return null
        val play = buttons.firstNotNullOfOrNull {
            it.o("musicPlayButtonRenderer").o("playNavigationEndpoint")
        }
        return LibraryState(
            playlistId = play.o("watchPlaylistEndpoint").s("playlistId")
                ?: play.o("watchEndpoint").s("playlistId")
                ?: return null,
            saved = save.s("isToggled") == "true",
        )
    }

    private val JsonElement?.isSaveToggle: Boolean
        get() = o("defaultIcon").s("iconType") == "BOOKMARK_BORDER" ||
            o("toggledIcon").s("iconType") == "BOOKMARK"

    fun parsePlaylistOwned(root: JsonElement): Boolean? {
        if (collectRenderers(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty()) {
            return true
        }
        val header = collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: return null
        if (collectRenderers(header, "menuNavigationItemRenderer")
                .any { it.o("icon").s("iconType") in OWNER_ICONS }
        ) {
            return true
        }
        return header.a("buttons").orEmpty().none { it.o("toggleButtonRenderer").isSaveToggle }
    }

    private val OWNER_ICONS = setOf("DELETE", "EDIT")

    fun parseUserPlaylists(root: JsonElement): List<UserPlaylist> =
        parseLibraryItems(root).mapNotNull { item ->
            val browseId = item.browseId ?: return@mapNotNull null
            if (!browseId.startsWith("VL")) return@mapNotNull null
            if (NOT_EDITABLE.any { browseId.startsWith("VL$it") }) return@mapNotNull null
            UserPlaylist(
                playlistId = browseId.removePrefix("VL"),
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }

    private val NOT_EDITABLE = listOf("LM", "SE", "RD", "OLAK", "MPRE")













    fun paramsFor(scope: String): String? = when (scope.lowercase()) {
        "all" -> SearchFilter.ALL.params
        "albums" -> SearchFilter.ALBUMS.params
        "artists" -> SearchFilter.ARTISTS.params
        "playlists" -> SearchFilter.PLAYLISTS.params
        "videos" -> SearchFilter.VIDEOS.params
        "songs" -> SearchFilter.SONGS.params
        else -> SearchFilter.ALL.params
    }

    private val DURATION = Regex("""\d+:\d{2}""")












    private fun parseCardShelfSong(renderer: JsonObject): Song? {
        val videoId = renderer.o("onTap").o("watchEndpoint").s("videoId") ?: return null
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null

        val subtitleRuns = renderer.o("subtitle").a("runs").orEmpty()
        val subtitle = subtitleRuns.joinToString("") { it.s("text").orEmpty() }
        val parts = subtitle.split(" • ").filter { it.isNotBlank() }
        val duration = parts.lastOrNull()?.takeIf { it.matches(DURATION) }
        val rowType = parts.firstOrNull { it.lowercase() in TYPE_WORDS }?.lowercase()
        val credits = creditsOf(subtitleRuns)
        val creditedArtists = artistNamesFromRuns(subtitleRuns)
        val artist = parts.firstOrNull {
            !it.matches(DURATION) && it.lowercase() !in TYPE_WORDS && !it.matches(TALLY)
        }
        val thumbnails = renderer.o("thumbnail").o("musicThumbnailRenderer")
            .o("thumbnail").a("thumbnails")

        return Song(
            videoId = videoId,
            title = title,
            artist = creditedArtists
                ?: credits.artistName?.takeIf { it.isNotBlank() }
                ?: artist
                ?: "Unknown artist",
            thumbnailUrl = thumbnails.best(),
            durationText = duration,
            artistId = credits.artistId,
            albumId = credits.albumId,
            albumName = credits.albumName,


            isVideo = rowType == "video" || thumbnails.isNotSquare(),
            isExplicit = renderer["subtitleBadges"].hasExplicitBadge(),
        )
    }
















    private fun cardShelfCredit(card: JsonObject): Credits? {
        val endpoint = card.o("onTap").o("browseEndpoint") ?: return null
        val pageType = endpoint.o("browseEndpointContextSupportedConfigs")
            .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
        if ("ARTIST" !in pageType) return null
        val name = card.o("title").runs().takeIf { it.isNotBlank() } ?: return null



        return Credits(artistId = endpoint.s("browseId"), artistName = name)
    }





    private fun parseCardShelfBrowse(renderer: JsonObject): BrowseItem? {
        val endpoint = renderer.o("onTap").o("browseEndpoint") ?: return null
        val browseId = endpoint.s("browseId") ?: return null
        val pageType = endpoint.o("browseEndpointContextSupportedConfigs")
            .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null
        val subtitle = renderer.o("subtitle").runs()


        if (VIDEO_WORD.containsMatchIn(title) || VIDEO_WORD.containsMatchIn(subtitle)) return null
        return BrowseItem(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = renderer.o("thumbnail").o("musicThumbnailRenderer")
                .o("thumbnail").a("thumbnails").best(),
            type = browseTypeOf(pageType),
        )
    }






    private fun artistNamesFromRuns(runs: List<JsonElement>): String? {
        val groups = mutableListOf<MutableList<JsonElement>>(mutableListOf())
        runs.forEach { run ->
            if (run.s("text")?.trim() == "•") {
                groups += mutableListOf<JsonElement>()
            } else {
                groups.last() += run
            }
        }
        val artistGroup = groups.firstOrNull { group ->
            group.any { run ->
                val pageType = run.o("navigationEndpoint").o("browseEndpoint")
                    .o("browseEndpointContextSupportedConfigs")
                    .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
                "ARTIST" in pageType
            }
        } ?: return null
        return artistGroup.mapIndexedNotNull { index, run ->
            if (index % 2 != 0) return@mapIndexedNotNull null
            run.s("text")?.trim()?.takeIf { it.isNotBlank() }
        }.distinct().joinToString(", ").ifBlank { null }
    }






    fun artistFromSubtitle(subtitle: String): String {
        val parts = subtitle.split(" • ").map(String::trim).filter { it.isNotBlank() }
        return parts.firstOrNull {
            it.lowercase() !in TYPE_WORDS && !it.matches(TALLY) && !it.matches(DURATION)
        } ?: subtitle
    }

    private fun browseTypeOf(pageType: String): BrowseType = when {
        "ALBUM" in pageType -> BrowseType.ALBUM
        "PLAYLIST" in pageType -> BrowseType.PLAYLIST
        "ARTIST" in pageType -> BrowseType.ARTIST
        else -> BrowseType.OTHER
    }

    private val TALLY = Regex(
        """[\d.,]+\s*[KMB]?\s+(plays|views|likes|songs|tracks|subscribers|""" +
            """hours?|minutes?|seconds?)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    private val TYPE_WORDS = setOf(
        "song", "video", "album", "single", "ep", "artist",
        "playlist", "podcast", "episode",
    )
    private val VIDEO_WORD = Regex("""\bvideos?\b""", RegexOption.IGNORE_CASE)
    private const val PODCASTS_CHIP = "SgQIDBAD"












    private fun JsonElement?.hasExplicitBadge(): Boolean? {
        val element = this ?: return null
        val badges = (element as? JsonObject)?.a("subtitleBadges").orEmpty()
        if (badges.isEmpty()) return null
        val explicit = badges.any { badge ->
            val icon = badge.o("musicInlineBadgeRenderer").s("icon")
            icon?.contains("EXPLICIT", ignoreCase = true) == true
        }
        return if (explicit) true else null
    }
    private val YEAR = Regex("""\d{4}""")
    private val RELEASE_WORDS = setOf("album", "single", "ep")
    private val HEADER_RENDERERS = listOf(
        "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer",
    )


    private val HEADER_CREDIT_LINES = listOf("straplineTextOne", "subtitle")
}



internal fun JsonElement?.o(key: String): JsonObject? =
    (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.a(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

internal fun JsonElement?.s(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.runs(): String =
    this.a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()


internal fun JsonElement?.firstRunText(): String? =
    this.a("runs")?.firstOrNull().s("text")



internal fun JsonArray?.best(): String? = this?.lastOrNull().s("url")

internal fun JsonArray?.isNotSquare(): Boolean {
    val last = this?.lastOrNull()
    val width = last.s("width")?.toDoubleOrNull() ?: return false
    val height = last.s("height")?.toDoubleOrNull() ?: return false
    if (width <= 0 || height <= 0) return false
    return width / height !in 0.85..1.15
}
