package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.SearchHit
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json





object SearchBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun interface SearchCallback {

        fun onResult(json: String?, continuation: String?, message: String?)
    }

    fun search(query: String, scope: String, callback: SearchCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                val response = Innertube.search(query, InnertubeParser.paramsFor(scope))


                val includeVideos = scope == SearchFilter.VIDEOS.name
                val hits = InnertubeParser.parseSearchPage(response, includeVideos)
                    .map { result -> hitOf(result) }
                val continuation = InnertubeParser.continuationToken(response)
                Innertube.checkSession(generation)
                callback.onResult(json.encodeToString(ListSerializer(SearchHit.serializer()), hits), continuation, null)
            } catch (e: Throwable) {
                callback.onResult(null, null, e.message ?: e.toString())
            }
        }
    }






    fun searchContinuation(token: String, scope: String, callback: SearchCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                val response = Innertube.searchContinuation(token)
                val includeVideos = scope == SearchFilter.VIDEOS.name
                val hits = InnertubeParser.parseSearchPage(response, includeVideos)
                    .map { result -> hitOf(result) }
                val continuation = InnertubeParser.continuationToken(response)
                Innertube.checkSession(generation)
                callback.onResult(json.encodeToString(ListSerializer(SearchHit.serializer()), hits), continuation, null)
            } catch (e: Throwable) {
                callback.onResult(null, null, e.message ?: e.toString())
            }
        }
    }







    fun searchTypeahead(query: String, callback: SearchCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                val response = Innertube.searchTypeahead(query)
                val hits = InnertubeParser.parseSearchPage(response, includeVideos = false)
                    .map { result -> hitOf(result) }
                Innertube.checkSession(generation)
                callback.onResult(json.encodeToString(ListSerializer(SearchHit.serializer()), hits), null, null)
            } catch (e: Throwable) {
                callback.onResult(null, null, e.message ?: e.toString())
            }
        }
    }





    private fun hitOf(result: SearchResult): SearchHit = when (result) {
        is SearchResult.TopTrack -> trackHit(result.song, kind = "top")
        is SearchResult.Track -> trackHit(result.song, kind = "track")
        is SearchResult.Browse -> SearchHit(
            kind = "browse",
            title = result.item.title,
            subtitle = result.item.subtitle,
            thumbnailUrl = result.item.thumbnailUrl,
            browseId = result.item.browseId,
            browseType = result.item.type.name,
        )
    }

    private fun trackHit(song: com.bxane.nuvx.data.model.Song, kind: String) = SearchHit(
        kind = kind,
        videoId = song.videoId,
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        durationText = song.durationText,
        albumName = song.albumName,
        artistId = song.artistId,
        albumId = song.albumId,
        isVideo = song.isVideo,
        setVideoId = song.setVideoId,
    )

}
