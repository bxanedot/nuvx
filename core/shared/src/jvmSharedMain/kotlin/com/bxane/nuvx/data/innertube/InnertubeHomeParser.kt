// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.MoodGenre
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfLayout
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.shelfLayoutFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object InnertubeHomeParser {
    fun podcastSearchShelf(results: List<SearchResult>): HomeShelf? {
        val items = results.mapNotNull { result ->
            (result as? SearchResult.Track)?.song?.let { song ->
                ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, isVideo = true)
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let {
            HomeShelf("Podcasts on YouTube", it, "Long-form episodes", layout = ShelfLayout.VIDEO)
        }
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

    fun parseTwoRowItemsDeep(root: JsonElement): List<ShelfItem> =
        InnertubeParser.collectRenderers(root, "musicTwoRowItemRenderer")
            .mapNotNull(InnertubeParser::parseTwoRowItem)

    private fun carouselShelf(carousel: JsonObject): HomeShelf? {
        val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
        val title = header.o("title").runs()
        val strapline = header.o("strapline").runs()
        val contents = carousel.a("contents").orEmpty()
        val parsed = contents.mapNotNull { item ->
            InnertubeParser.parseTwoRowItem(item.o("musicTwoRowItemRenderer"))
                ?: InnertubeParser.parseResponsiveListItem(item.o("musicResponsiveListItemRenderer"))
                    ?.let { song ->
                        ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, isVideo = song.isVideo)
                    }
                ?: parseArtistRow(item.o("musicResponsiveListItemRenderer"))
                ?: InnertubeParser.parseMultiRowListItem(item.o("musicMultiRowListItemRenderer"), null)
                    ?.let { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        }
        val items = if (InnertubeParser.VIDEO_WORD.containsMatchIn(title)) parsed.filter { it.videoId != null } else parsed
        if (items.isEmpty()) return null
        val shelfTitle = title.ifBlank { "For you" }
        val first = contents.firstOrNull()
        val fromList = first.o("musicResponsiveListItemRenderer") != null || first.o("musicMultiRowListItemRenderer") != null
        return HomeShelf(shelfTitle, items, strapline, layout = shelfLayoutFor(shelfTitle, items, fromList))
    }

    private fun plainShelf(shelf: JsonObject): HomeShelf? {
        val title = shelf.o("title").runs()
        val items = shelf.a("contents").orEmpty().mapNotNull {
            InnertubeParser.parseResponsiveListItem(it.o("musicResponsiveListItemRenderer"))
        }.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, isVideo = it.isVideo) }
        if (items.isEmpty()) return null
        val shelfTitle = title.ifBlank { "For you" }
        return HomeShelf(shelfTitle, items, layout = shelfLayoutFor(shelfTitle, items, fromList = true))
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

    private const val PODCASTS_CHIP = "SgQIDBAD"
}
