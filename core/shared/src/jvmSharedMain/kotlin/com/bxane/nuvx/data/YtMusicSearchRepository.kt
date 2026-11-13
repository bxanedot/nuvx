

package com.bxane.nuvx.data

import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult

internal object YtMusicSearchRepository {
    suspend fun searchPage(
        query: String,
        filter: SearchFilter,
        anonymous: Boolean,
    ): Result<YtMusicRepository.SearchPage> =
        if (filter == SearchFilter.YOUTUBE) {
            YtMusicRepository.call("search:youtube") {
                YtMusicRepository.SearchPage(InnertubeParser.parseYoutubeSearch(Innertube.youtubeSearch(query)), null)
            }
        } else {
            YtMusicRepository.call("search:${filter.name}") {
                InnertubeParser.parseSearchPage(
                    Innertube.search(query, filter.params, anonymous),
                    includeVideos = filter == SearchFilter.VIDEOS,
                ).let { page ->
                    YtMusicRepository.SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
                }
            }
        }

    suspend fun searchContinuation(
        token: String,
        filter: SearchFilter,
    ): Result<YtMusicRepository.SearchPage> = YtMusicRepository.call("search:continuation") {
        InnertubeParser.parseSearchPage(
            Innertube.searchContinuation(token),
            includeVideos = filter == SearchFilter.VIDEOS,
        ).let { page ->
            YtMusicRepository.SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
        }
    }

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        searchPage(query, filter, true).map { it.rows }

    suspend fun searchSuggestions(input: String): Result<List<String>> =
        YtMusicRepository.call("suggest") {
            InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
        }

    suspend fun searchTypeahead(input: String): Result<YtMusicRepository.SearchPage> =
        YtMusicRepository.call("typeahead:$input") {
            InnertubeParser.parseSearchPage(
                Innertube.searchTypeahead(input),
                includeVideos = false,
            ).let { page ->
                YtMusicRepository.SearchPage(page.rows.distinctBy { it.identityKey() }, page.continuation)
            }
        }

    private fun SearchResult.identityKey(): String = when (this) {
        is SearchResult.TopTrack -> "v:${song.videoId}"
        is SearchResult.Track -> "v:${song.videoId}"
        is SearchResult.Browse -> "b:${item.browseId}"
    }
}
