

package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import kotlinx.serialization.json.JsonObject

internal object InnertubeSearchParser {
    fun parseSearchSongs(response: JsonObject): List<Song> =
        parseSearch(response).filterIsInstance<SearchResult.Track>().map { it.song }

    fun parseSearch(response: JsonObject): List<SearchResult> = parseSearchPage(response).rows

    fun parseSearchPage(response: JsonObject, includeVideos: Boolean = false): InnertubeParser.SearchPage {
        val topResults: List<SearchResult> = if (includeVideos) emptyList() else {
            InnertubeParser.collectRenderers(response, "musicCardShelfRenderer")
                .mapNotNull { card ->
                    InnertubeParser.parseCardShelfSong(card)?.let(SearchResult::TopTrack)
                        ?: InnertubeParser.parseCardShelfBrowse(card)?.let(SearchResult::Browse)
                }
        }
        val rows = InnertubeParser.collectRenderers(response, "musicResponsiveListItemRenderer")

        val cardCredits: List<Pair<JsonObject, InnertubeParser.Credits>> = if (includeVideos) emptyList() else {
            InnertubeParser.collectRenderers(response, "musicCardShelfRenderer").flatMap { card ->
                val credit = InnertubeParser.cardShelfCredit(card) ?: return@flatMap emptyList()
                InnertubeParser.collectRenderers(card, "musicResponsiveListItemRenderer").map { it to credit }
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
                val browse = InnertubeParser.parseBrowseItem(renderer)
                if (browse != null) {
                    if (seen.add("b:${browse.browseId}")) add(SearchResult.Browse(browse))
                } else {
                    val fallback = cardCredits.firstOrNull { it.first === renderer }?.second
                    InnertubeParser.parseResponsiveListItem(renderer, fallback ?: InnertubeParser.Credits())?.let { song ->
                        if (song.isVideo == includeVideos && seen.add("v:${song.videoId}")) {
                            add(SearchResult.Track(song))
                        }
                    }
                }
            }
        }
        return InnertubeParser.SearchPage(parsed, InnertubeParser.continuationToken(response))
    }

    fun parseSearchSuggestions(response: JsonObject): List<String> =
        InnertubeParser.collectRenderers(response, "searchSuggestionRenderer")
            .mapNotNull { renderer ->
                val query = renderer.o("navigationEndpoint").o("searchEndpoint").s("query")
                    ?: renderer.o("suggestion").runs()
                query.takeIf { it.isNotBlank() }
            }
            .distinct()

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
}
