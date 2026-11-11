package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.auth.normalizeDataSyncId
import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.model.AccountChannel
import com.bxane.nuvx.data.model.ArtistNameIndex
import com.bxane.nuvx.data.model.ArtistPage
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.completeArtistCredits
import com.bxane.nuvx.data.model.BrowseItem
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.shelfLayoutFor
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SongMenu
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.VersionPair
import com.bxane.nuvx.data.model.VersionTimeMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

object InnertubeParser {

    fun parseSearchSongs(response: JsonObject): List<Song> =
        parseSearch(response).filterIsInstance<SearchResult.Track>().map { it.song }

    data class SearchPage(val rows: List<SearchResult>, val continuation: String?)

    fun parseSearch(response: JsonObject): List<SearchResult> = parseSearchPage(response).rows

    fun parseSearchPage(response: JsonObject, includeVideos: Boolean = false): SearchPage {
        val topResults: List<SearchResult> = if (includeVideos) emptyList() else {
            collectRenderers(response, "musicCardShelfRenderer")
                .mapNotNull { card ->
                    parseCardShelfSong(card)?.let(SearchResult::TopTrack)
                        ?: parseCardShelfBrowse(card)?.let(SearchResult::Browse)
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
        val parsed = buildList {
            topResults.forEach { result ->
                when (result) {
                    is SearchResult.TopTrack -> {
                        if (!result.song.isVideo && seen.add("v:${result.song.videoId}")) add(result)
                    }
                    is SearchResult.Browse -> {
                        if (seen.add("b:${result.item.browseId}")) add(result)
                    }
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
                        if (song.isVideo == includeVideos && seen.add("v:${song.videoId}")) {
                            add(SearchResult.Track(song))
                        }
                    }
                }
            }
        }
        return SearchPage(parsed, continuationToken(response))
    }

    fun parseSearchSuggestions(response: JsonObject): List<String> =
        collectRenderers(response, "searchSuggestionRenderer")
            .mapNotNull { renderer ->
                val query = renderer.o("navigationEndpoint").o("searchEndpoint").s("query")
                    ?: renderer.o("suggestion").runs()
                query.takeIf { it.isNotBlank() }
            }
            .distinct()

    internal fun collectRenderers(root: JsonElement, name: String): List<JsonObject> {
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

    fun parseHome(response: JsonObject): List<HomeShelf> {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        return sections.mapNotNull { section ->
            section.o("musicCarouselShelfRenderer")?.let(::carouselShelf)
                ?: section.o("musicShelfRenderer")?.let(::plainShelf)
        }
    }

    fun parseYoutubeSearch(root: JsonObject): List<SearchResult> =
        root.o("contents").o("twoColumnSearchResultsRenderer").o("primaryContents")
            .o("sectionListRenderer").a("contents").orEmpty()
            .flatMap { it.o("itemSectionRenderer").a("contents").orEmpty() }
            .mapNotNull { item ->
                val r = item.o("videoRenderer") ?: return@mapNotNull null
                val videoId = r.s("videoId") ?: return@mapNotNull null
                val length = r.o("lengthText").s("simpleText") ?: return@mapNotNull null
                val title = r.o("title").runs().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SearchResult.Track(
                    Song(
                        videoId = videoId,
                        title = title,
                        artist = r.o("ownerText").runs().ifBlank { "YouTube" },
                        thumbnailUrl = r.o("thumbnail").a("thumbnails").best(),
                        durationText = length,
                        isVideo = true,
                    ),
                )
            }

    fun podcastsChipParams(home: JsonObject): String? {
        val chips = home.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").o("header")
            .o("chipCloudRenderer").a("chips").orEmpty()
        return chips.firstNotNullOfOrNull { element ->
            val chip = element.o("chipCloudChipRenderer") ?: return@firstNotNullOfOrNull null
            val params = chip.o("navigationEndpoint").o("browseEndpoint").s("params") ?: return@firstNotNullOfOrNull null
            val title = chip.o("text").runs()
            params.takeIf { title.equals("Podcasts", ignoreCase = true) || PODCASTS_CHIP in params }
        }
    }

    private const val PODCASTS_CHIP = "SgQIDBAD"

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

    private fun carouselShelf(carousel: JsonObject): HomeShelf? {
        val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
        val title = header.o("title").runs()
        val strapline = header.o("strapline").runs()
        val contents = carousel.a("contents").orEmpty()
        val parsed = contents.mapNotNull { item ->
            parseTwoRowItem(item.o("musicTwoRowItemRenderer"))
                ?: parseResponsiveListItem(item.o("musicResponsiveListItemRenderer"))
                    ?.let { song ->
                        ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, isVideo = song.isVideo)
                    }
                ?: parseArtistRow(item.o("musicResponsiveListItemRenderer"))
                ?: parseMultiRowListItem(item.o("musicMultiRowListItemRenderer"), null)
                    ?.let { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        }
        val items = if (VIDEO_WORD.containsMatchIn(title)) parsed.filter { it.videoId != null } else parsed
        if (items.isEmpty()) return null
        val shelfTitle = title.ifBlank { "For you" }
        val first = contents.firstOrNull()
        val fromList = first.o("musicResponsiveListItemRenderer") != null || first.o("musicMultiRowListItemRenderer") != null
        return HomeShelf(shelfTitle, items, strapline, layout = shelfLayoutFor(shelfTitle, items, fromList))
    }

    private fun plainShelf(shelf: JsonObject): HomeShelf? {
        val title = shelf.o("title").runs()
        val items = shelf.a("contents").orEmpty().mapNotNull {
            parseResponsiveListItem(it.o("musicResponsiveListItemRenderer"))
        }.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, isVideo = it.isVideo) }
        if (items.isEmpty()) return null
        val shelfTitle = title.ifBlank { "For you" }
        return HomeShelf(shelfTitle, items, layout = shelfLayoutFor(shelfTitle, items, fromList = true))
    }

    fun parseArtistPage(response: JsonObject): ArtistPage {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        val songs = mutableListOf<Song>()
        var moreSongs: String? = null
        val shelves = mutableListOf<HomeShelf>()
        val header = response["header"]
        val credit = Credits(artistName = InnertubeArtistHeaderParser.artistName(header))

        sections.forEach { section ->
            section.o("musicShelfRenderer")?.let { shelf ->
                shelf.a("contents").orEmpty().forEach { row ->
                    parseResponsiveListItem(row.o("musicResponsiveListItemRenderer"), credit)
                        ?.let(songs::add)
                }
                if (moreSongs == null) {
                    moreSongs = shelf.o("title").a("runs")?.firstOrNull()
                        .o("navigationEndpoint").o("browseEndpoint").s("browseId")
                }
            }
            section.o("musicCarouselShelfRenderer")?.let { carousel ->
                val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
                val title = header.o("title").runs()
                val items = carousel.a("contents").orEmpty().mapNotNull {
                    parseTwoRowItem(it.o("musicTwoRowItemRenderer"))
                }.filter { it.browseId != null || it.isVideo }
                val moreEndpoint = header.o("title").a("runs")?.firstNotNullOfOrNull {
                    it.o("navigationEndpoint").o("browseEndpoint")
                }
                    ?: header.o("moreContentButton").o("buttonRenderer")
                        .o("navigationEndpoint").o("browseEndpoint")
                    ?: header.o("moreContentButton").o("musicMoreContentButtonRenderer")
                        .o("navigationEndpoint").o("browseEndpoint")
                    ?: header.a("endIcons")?.firstNotNullOfOrNull {
                        it.o("musicNavigationButtonRenderer").o("clickCommand").o("browseEndpoint")
                            ?: it.o("musicNavigationButtonRenderer").o("navigationEndpoint").o("browseEndpoint")
                    }
                    ?: header.o("navigationEndpoint").o("browseEndpoint")
                val moreBrowseId = moreEndpoint.s("browseId")
                val moreParams = moreEndpoint.s("params")
                if (title.isNotBlank() && items.isNotEmpty()) {
                    shelves += HomeShelf(
                        title = title,
                        items = items,
                        moreBrowseId = moreBrowseId,
                        moreParams = moreParams,
                        layout = shelfLayoutFor(title, items, fromList = false),
                    )
                }
            }
        }
        return ArtistPage(
            songs, moreSongs, shelves,
            thumbnailUrl = InnertubeArtistHeaderParser.artistThumbnail(header),
            name = credit.artistName,
            description = parseDescription(response),
            subscriberCountText = InnertubeArtistHeaderParser.subscriberCount(header),
            monthlyListenerCount = InnertubeArtistHeaderParser.monthlyListeners(header),
            subscription = InnertubeArtistHeaderParser.subscription(header),
            radio = InnertubeArtistHeaderParser.headerWatchTarget(header, "startRadioButton"),
            shuffle = InnertubeArtistHeaderParser.headerWatchTarget(header, "playButton"),
        )
    }

    fun parseTwoRowItemsDeep(root: JsonElement): List<ShelfItem> =
        collectRenderers(root, "musicTwoRowItemRenderer").mapNotNull(::parseTwoRowItem)

    fun collectSongsDeep(root: JsonElement, show: String? = null): List<Song> {
        val out = LinkedHashMap<String, Song>()
        val pageCredit = pageCredit(root)
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["musicResponsiveListItemRenderer"]?.let { renderer ->
                        parseResponsiveListItem(renderer as? JsonObject, pageCredit)
                            ?.let { out[it.videoId] = it }
                    }
                    node["musicMultiRowListItemRenderer"]?.let { renderer ->
                        parseMultiRowListItem(renderer as? JsonObject, show)
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

    fun podcastShow(root: JsonElement): String? {
        if (collectRenderers(root, "musicMultiRowListItemRenderer").isEmpty()) return null
        return parseBrowseHeader(root)?.title
    }

    data class PlaylistShelfPage(val songs: List<Song>, val suggested: List<Song>, val continuation: String?)

    fun parsePlaylistShelf(root: JsonElement): PlaylistShelfPage? {
        val scope: JsonElement = root.o("continuationContents")
            ?: root.o("contents").o("twoColumnBrowseResultsRenderer").o("secondaryContents")
            ?: return null
        val playlistScope = scope.o("musicPlaylistShelfContinuation")
            ?: collectRenderers(scope, "musicPlaylistShelfRenderer").firstOrNull()
        val suggestionShelves = collectRenderers(scope, "musicShelfRenderer")
            .filter { it.o("title").runs() == "Suggestions" }
        if (playlistScope == null && suggestionShelves.isEmpty()) return null

        val pageCredit = pageCredit(root)
        val songs = playlistScope?.let { playlist ->
            collectRenderers(playlist, "musicResponsiveListItemRenderer")
                .mapNotNull { parseResponsiveListItem(it, pageCredit) }
                .distinctBy { it.videoId }
        }.orEmpty()
        val knownSongs = songs.mapTo(HashSet()) { it.videoId }
        val suggested = suggestionShelves
            .flatMap { collectRenderers(it, "musicResponsiveListItemRenderer") }
            .mapNotNull { parseResponsiveListItem(it, pageCredit) }
            .filterNot { it.videoId in knownSongs }
            .distinctBy { it.videoId }
        val token = playlistScope?.let { continuationToken(it) }
        return PlaylistShelfPage(songs, suggested, token)
    }

    data class LibraryItemPage(val items: List<ShelfItem>, val continuation: String?)

    fun parseLibraryItems(root: JsonElement): List<ShelfItem> = parseLibraryItemPage(root).items

    fun parseLibraryItemPage(root: JsonElement): LibraryItemPage {
        val out = LinkedHashMap<String, ShelfItem>()
        collectRenderers(root, "musicTwoRowItemRenderer").forEach { renderer ->
            val item = parseTwoRowItem(renderer) ?: return@forEach
            item.browseId?.let { out.putIfAbsent(it, item) }
        }
        collectRenderers(root, "musicResponsiveListItemRenderer").forEach { renderer ->
            val item = parseBrowseItem(renderer) ?: return@forEach
            out.putIfAbsent(
                item.browseId,
                ShelfItem(item.title, item.subtitle, item.thumbnailUrl, null, item.browseId),
            )
        }
        return LibraryItemPage(out.values.toList(), continuationToken(root))
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

        if (renderer.isPrivateUpload()) UploadedTracks.mark(videoId)

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
        val rowType = parts.firstOrNull { it.lowercase(Locale.ROOT) in TYPE_WORDS }?.lowercase(Locale.ROOT)
        val artist = parts.firstOrNull {
            !it.matches(DURATION) && it.lowercase(Locale.ROOT) !in TYPE_WORDS && !it.matches(TALLY)
        }

        val credits = creditsOf(
            columns.flatMap {
                it.o("musicResponsiveListItemFlexColumnRenderer").o("text").a("runs").orEmpty()
            },
        )
        val creditedArtists = artistNamesFromRuns(subtitleRuns)
        val episode = rowType == "episode" || renderer.isPodcastEpisode()
        val show = if (episode) showNameFromRuns(subtitleRuns) else null

        val thumbnails = renderer.o("thumbnail").o("musicThumbnailRenderer")
            .o("thumbnail").a("thumbnails")

        return Song(
            videoId = videoId,
            title = title,
            artist = creditedArtists
                ?: credits.artistName?.takeIf { it.isNotBlank() }
                ?: show
                ?: artist
                ?: fallback.artistName
                ?: "Unknown artist",
            thumbnailUrl = thumbnails.best(),
            durationText = duration,
            artistId = credits.artistId ?: fallback.artistId,
            artists = completeArtistCredits(artist.orEmpty(), credits.artists.ifEmpty { fallback.artists }),
            albumId = credits.albumId ?: fallback.albumId,
            albumName = credits.albumName ?: fallback.albumName,
            setVideoId = renderer.o("playlistItemData").s("playlistSetVideoId"),
            isVideo = !episode && (rowType == "video" || thumbnails.isNotSquare()),
            isExplicit = renderer.hasExplicitBadge(),
            feedbackToken = extractFeedbackToken(renderer),
        )
    }

    private fun parseMultiRowListItem(renderer: JsonObject?, show: String?): Song? {
        if (renderer == null) return null
        val videoId = renderer.o("onTap").o("watchEndpoint").s("videoId")
            ?: renderer.o("overlay")
                .o("musicItemThumbnailOverlayRenderer").o("content")
                .o("musicPlayButtonRenderer").o("playNavigationEndpoint")
                .o("watchEndpoint").s("videoId")
            ?: return null
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null
        return Song(
            videoId = videoId,
            title = title,
            artist = renderer.o("secondTitle").runs().takeIf { it.isNotBlank() }
                ?: show
                ?: "Unknown artist",
            thumbnailUrl = renderer.o("thumbnail").o("musicThumbnailRenderer")
                .o("thumbnail").a("thumbnails").best(),
            durationText = episodeRuntime(
                renderer.o("playbackProgress").o("musicPlaybackProgressRenderer")
                    .o("durationText").runs(),
            ),
            isExplicit = renderer.hasExplicitBadge(),
        )
    }

    private fun episodeRuntime(text: String): String? {
        var seconds = 0L
        RUNTIME_PART.findAll(text).forEach { part ->
            val count = part.groupValues[1].toLong()
            seconds += when (part.groupValues[2].lowercase(Locale.ROOT).first()) {
                'h' -> count * 3_600
                'm' -> count * 60
                else -> count
            }
        }
        if (seconds == 0L) return null
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    private fun showNameFromRuns(runs: List<JsonElement>): String? =
        runs.firstOrNull { run ->
            run.o("navigationEndpoint").o("browseEndpoint")
                .o("browseEndpointContextSupportedConfigs")
                .o("browseEndpointContextMusicConfig").s("pageType")
                .orEmpty().contains("PODCAST_SHOW")
        }?.s("text")?.trim()?.takeIf { it.isNotBlank() }

    private fun parseCardShelfSong(renderer: JsonObject): Song? {
        val videoId = renderer.o("onTap").o("watchEndpoint").s("videoId") ?: return null
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null

        val subtitleRuns = renderer.o("subtitle").a("runs").orEmpty()
        val subtitle = subtitleRuns.joinToString("") { it.s("text").orEmpty() }
        val parts = subtitle.split(" • ").filter { it.isNotBlank() }
        val duration = parts.lastOrNull()?.takeIf { it.matches(DURATION) }
        val rowType = parts.firstOrNull { it.lowercase(Locale.ROOT) in TYPE_WORDS }
            ?.lowercase(Locale.ROOT)
        val credits = creditsOf(subtitleRuns)
        val creditedArtists = artistNamesFromRuns(subtitleRuns)
        val artist = parts.firstOrNull {
            !it.matches(DURATION) && it.lowercase(Locale.ROOT) !in TYPE_WORDS && !it.matches(TALLY)
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
            artists = completeArtistCredits(artist.orEmpty(), credits.artists),
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
        if ("NON_MUSIC_AUDIO_TRACK" in pageType) return null
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
            type = when {
                "ALBUM" in pageType -> BrowseType.ALBUM
                "ARTIST" in pageType -> BrowseType.ARTIST
                "PLAYLIST" in pageType -> BrowseType.PLAYLIST
                else -> BrowseType.OTHER
            },
        )
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

    private data class Credits(
        val artistId: String? = null,
        val artistName: String? = null,
        val artists: List<ArtistRef> = emptyList(),
        val albumId: String? = null,
        val albumName: String? = null,
    )

    private fun creditsOf(runs: List<JsonElement>): Credits {
        var credits = Credits()
        val artists = mutableListOf<ArtistRef>()
        runs.forEach { run ->
            val browse = run.o("navigationEndpoint").o("browseEndpoint")
            val id = browse.s("browseId") ?: return@forEach
            val pageType = browse.o("browseEndpointContextSupportedConfigs")
                .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()
            if ("ARTIST" !in pageType && "ALBUM" !in pageType) return@forEach
            val name = run.s("text").orEmpty()
            if ("ARTIST" in pageType) {
                if (name.isNotBlank()) {
                    artists += ArtistRef(name = name, browseId = id)
                }
                if (credits.artistId == null) {
                    credits = credits.copy(artistId = id, artistName = name)
                }
            } else if (credits.albumId == null) {
                credits = credits.copy(albumId = id, albumName = name)
            }
        }
        ArtistNameIndex.record(artists)
        return if (artists.isEmpty()) credits else credits.copy(artists = artists)
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

    private fun pageCredit(root: JsonElement): Credits {
        val header = HEADER_RENDERERS.firstNotNullOfOrNull {
            collectRenderers(root, it).firstOrNull()
        } ?: return Credits()
        val lines = HEADER_CREDIT_LINES.map { header.o(it).a("runs").orEmpty() }
        val parts = lines.flatMap { line ->
            line.joinToString("") { it.s("text").orEmpty() }.split(" • ", " · ").map(String::trim)
        }
        if (parts.none { it.lowercase(Locale.ROOT) in RELEASE_WORDS }) return Credits()

        val credits = creditsOf(lines.flatten())
        val creditedArtists = lines.firstNotNullOfOrNull(::artistNamesFromRuns)
        if (creditedArtists != null) return credits.copy(artistName = creditedArtists)
        val name = parts.firstOrNull {
            it.isNotBlank() && it.lowercase(Locale.ROOT) !in TYPE_WORDS && !it.matches(TALLY) &&
                !it.matches(YEAR) && !it.matches(DURATION)
        }
        return credits.copy(artistName = name)
    }

    data class BrowseHeader(
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
    )

    fun parseBrowseHeader(root: JsonElement): BrowseHeader? {
        return InnertubeBrowseHeaderParser.parseBrowseHeader(root)
    }

    fun parseAlbumPlaylistId(root: JsonElement): String? {
        return InnertubeBrowseHeaderParser.parseAlbumPlaylistId(root)
    }

    fun parseDescription(root: JsonElement): String? {
        return InnertubeBrowseHeaderParser.parseDescription(root)
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
            thumbnailUrl = header.o("accountPhoto").a("thumbnails").best(),
        )
    }

    fun parseAccountChannels(root: JsonElement): List<AccountChannel> =
        collectRenderers(root, "accountItem").mapNotNull { item ->
            val name = item.o("accountName").runs()
                .ifBlank { item.o("accountName").s("simpleText").orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            val pageId = item.findString("pageId")
            val dataSyncId = normalizeDataSyncId(item.findString("datasyncIdToken"))
            if (pageId == null && dataSyncId == null) return@mapNotNull null
            AccountChannel(
                name = name,
                subtitle = item.o("channelHandle").runs()
                    .ifBlank { item.o("channelHandle").s("simpleText").orEmpty() }
                    .ifBlank { item.o("accountByline").runs() }
                    .ifBlank { item.o("accountByline").s("simpleText").orEmpty() },
                thumbnailUrl = item.o("accountPhoto").a("thumbnails").best(),
                pageId = pageId,
                dataSyncId = dataSyncId,
                activeOnWeb = (item["isSelected"] as? JsonPrimitive)?.content == "true",
            )
        }.distinctBy { it.key }

    fun watchQueuePairs(root: JsonElement): Pair<Int, Int> {
        val wrappers = collectRenderers(root, "playlistPanelVideoWrapperRenderer")
        return wrappers.size to wrappers.count { wrapper -> wrapperRenderers(wrapper).any { it.isAudioTrack() } }
    }

    private fun wrapperRenderers(wrapper: JsonObject): List<JsonObject> =
        listOfNotNull(wrapper.o("primaryRenderer").o("playlistPanelVideoRenderer")) +
            wrapper.a("counterpart").orEmpty().mapNotNull { it.o("counterpartRenderer").o("playlistPanelVideoRenderer") }

    private fun JsonObject.isAudioTrack(): Boolean = musicVideoType() == MUSIC_VIDEO_TYPE_ATV

    private fun JsonObject.musicVideoType(): String? =
        o("navigationEndpoint").o("watchEndpoint").o("watchEndpointMusicSupportedConfigs")
            .o("watchEndpointMusicConfig").s("musicVideoType")

    private const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"

    fun parseWatchQueue(root: JsonElement, preferAudio: Boolean = false, preferVideo: Boolean = false): List<Song> {
        val out = LinkedHashMap<String, Song>()
        val replacements = HashMap<String, JsonObject>()
        val dropped = HashSet<String>()
        if (preferAudio || preferVideo) {
            collectRenderers(root, "playlistPanelVideoWrapperRenderer").forEach { wrapper ->
                val halves = wrapperRenderers(wrapper)
                val primary = halves.firstOrNull() ?: return@forEach
                val audio = halves.firstOrNull { if (preferVideo) !it.isAudioTrack() else it.isAudioTrack() }
                    ?: return@forEach
                val primaryId = primary.s("videoId") ?: return@forEach
                replacements[primaryId] = audio
                halves.filter { it !== primary }.mapNotNullTo(dropped) { it.s("videoId") }
            }
        }
        collectRenderers(root, "playlistPanelVideoRenderer").forEach { found ->
            val foundId = found.s("videoId") ?: return@forEach
            if (foundId in dropped) return@forEach
            val song = panelSong(replacements[foundId] ?: found) ?: return@forEach
            out[song.videoId] = song
        }
        return out.values.toList()
    }

    private fun panelSong(renderer: JsonObject): Song? {
        val videoId = renderer.s("videoId") ?: return null
        val title = renderer.o("title").runs()
        if (title.isBlank()) return null
        val bylineRuns = renderer.o("longBylineText").a("runs").orEmpty()
        val byline = bylineRuns.map { it.s("text").orEmpty() }
        val artist = byline.takeWhile { !it.contains("•") }.joinToString("").trim()
        val credits = creditsOf(bylineRuns)
        return Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = renderer.o("thumbnail").a("thumbnails").best(),
            durationText = renderer.o("lengthText").runs().takeIf { it.isNotBlank() },
            artistId = credits.artistId,
            artists = completeArtistCredits(artist.orEmpty(), credits.artists),
            albumId = credits.albumId,
            albumName = credits.albumName,
            isVideo = byline.any { it.contains("views", ignoreCase = true) },
            isExplicit = renderer.hasExplicitBadge(),
        )
    }

    fun parseVersionPair(root: JsonElement, videoId: String, wantAudio: Boolean): VersionPair? {
        fun JsonObject.fits(): Boolean = musicVideoType()?.let { (it == MUSIC_VIDEO_TYPE_ATV) == wantAudio } ?: true

        collectRenderers(root, "playlistPanelVideoWrapperRenderer").forEach { wrapper ->
            val primary = wrapper.o("primaryRenderer").o("playlistPanelVideoRenderer") ?: return@forEach
            val counterparts = wrapper.a("counterpart").orEmpty().filterIsInstance<JsonObject>()
            fun JsonObject.renderer(): JsonObject? = o("counterpartRenderer").o("playlistPanelVideoRenderer")

            if (primary.s("videoId") == videoId) {
                val match = counterparts.firstOrNull { it.renderer()?.fits() == true } ?: return@forEach
                val song = panelSong(match.renderer() ?: return@forEach) ?: return@forEach
                return VersionPair(song.copy(isVideo = !wantAudio), timeMapOf(match))
            }
            val asCounterpart = counterparts.firstOrNull { it.renderer().s("videoId") == videoId } ?: return@forEach
            if (!primary.fits()) return@forEach
            val song = panelSong(primary) ?: return@forEach
            return VersionPair(song.copy(isVideo = !wantAudio), timeMapOf(asCounterpart)?.inverted())
        }
        return null
    }

    private fun timeMapOf(counterpart: JsonObject): VersionTimeMap? {
        val segments = counterpart.o("segmentMap").a("segment").orEmpty().mapNotNull { segment ->
            val from = segment.s("primaryVideoStartTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
            val to = segment.s("counterpartVideoStartTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
            val length = segment.s("durationMilliseconds")?.toLongOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            VersionTimeMap.Segment(fromStartMs = from, toStartMs = to, durationMs = length)
        }
        return segments.takeIf { it.isNotEmpty() }?.let(::VersionTimeMap)
    }

    private fun extractFeedbackToken(renderer: JsonObject): String? =
        renderer.o("menu")
            ?.o("menuRenderer")
            ?.a("items")
            ?.firstNotNullOfOrNull { item ->
                val service = item.o("menuServiceItemRenderer") ?: return@firstNotNullOfOrNull null
                if (service.o("icon")?.s("iconType") != "DELETE") return@firstNotNullOfOrNull null
                service.o("serviceEndpoint")?.o("feedbackEndpoint")?.s("feedbackToken")
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
            likeStatus = likeStatus,
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
        val save = buttons.firstNotNullOfOrNull { it.o("toggleButtonRenderer")?.takeIf { b -> b.isSaveToggle } }
            ?: return null
        if (save.s("isDisabled") == "true") return null
        val play = buttons.firstNotNullOfOrNull { it.o("musicPlayButtonRenderer").o("playNavigationEndpoint") }
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
        parseUserPlaylists(parseLibraryItems(root))

    fun parseUserPlaylists(items: List<ShelfItem>): List<UserPlaylist> =
        items.mapNotNull { item ->
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
            thumbnails.isNotSquare() ||
                renderer.s("aspectRatio")?.contains("16_9") == true
            )
        if (resolvedBrowseId != null &&
            !resolvedBrowseId.startsWith("VL") &&
            !resolvedBrowseId.startsWith("PL") &&
            !resolvedBrowseId.startsWith("MPRE") &&
            !resolvedBrowseId.startsWith("UC") &&
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

    fun artistFromSubtitle(subtitle: String): String {
        val parts = subtitle.split(" • ").map(String::trim).filter { it.isNotBlank() }
        return parts.firstOrNull {
            it.lowercase() !in TYPE_WORDS && !it.matches(TALLY) && !it.matches(DURATION)
        } ?: subtitle
    }

    private val NOT_EDITABLE = listOf("LM", "SE", "RD", "OLAK", "MPRE")

    private fun JsonElement?.hasExplicitBadge(): Boolean? =
        true.takeIf {
            when (this) {
                is JsonPrimitive -> contentOrNull == "MUSIC_EXPLICIT_BADGE"
                is JsonArray -> any { it.hasExplicitBadge() == true }
                is JsonObject -> values.any { it.hasExplicitBadge() == true }
                null -> false
            }
        }

    private val DURATION = Regex("""\d+:\d{2}""")
    private val RUNTIME_PART = Regex("""(\d+)\s*(hours?|hr|min|sec)""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""\d{4}""")
    private val TALLY = Regex(
        """[\d.,]+\s*[KMB]?\s+(plays|views|likes|songs|tracks|subscribers|""" +
            """hours?|minutes?|seconds?)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    private val RELEASE_WORDS = setOf("album", "single", "ep")
    internal val HEADER_RENDERERS = listOf(
        "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer",
    )
    internal val HEADER_CREDIT_LINES = listOf("straplineTextOne", "subtitle")
    private val TYPE_WORDS = setOf(
        "song", "video", "album", "single", "ep", "artist",
        "playlist", "podcast", "episode",
    )
    private val VIDEO_WORD = Regex("""\bvideos?\b""", RegexOption.IGNORE_CASE)
}

internal fun JsonElement?.o(key: String): JsonObject? =
    (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.a(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

internal fun JsonElement?.s(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.findString(key: String): String? = when (this) {
    is JsonObject -> {
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: values.firstNotNullOfOrNull { it.findString(key) }
    }
    is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
    else -> null
}

private fun JsonElement?.hasKey(key: String): Boolean = when (this) {
    is JsonObject -> containsKey(key) || values.any { it.hasKey(key) }
    is JsonArray -> any { it.hasKey(key) }
    else -> false
}

private fun JsonObject.isPodcastEpisode(): Boolean =
    findString("musicVideoType") == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"

private fun JsonObject.isPrivateUpload(): Boolean =
    hasKey("musicDeletePrivatelyOwnedEntityCommand") ||
        findString("musicVideoType")?.contains("PRIVATELY_OWNED", ignoreCase = true) == true

internal fun JsonElement?.runs(): String =
    this.a("runs")?.joinToString("") { it.s("text").orEmpty() }.orEmpty()

internal fun JsonElement?.firstRunText(): String? =
    this.a("runs")?.firstOrNull().s("text")

internal fun JsonArray?.best(): String? = this?.lastOrNull().s("url")

private fun JsonArray?.isNotSquare(): Boolean {
    val last = this?.lastOrNull()
    val width = last.s("width")?.toDoubleOrNull() ?: return false
    val height = last.s("height")?.toDoubleOrNull() ?: return false
    if (width <= 0 || height <= 0) return false
    return width / height !in 0.85..1.15
}
