package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.HomeFeed
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json






object LibraryBridge {

    private const val LIBRARY_SONGS = "FEmusic_liked_videos"

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val FEEDS = listOf(
        "Playlists" to "FEmusic_liked_playlists",
        "Albums" to "FEmusic_liked_albums",
        "Artists" to "FEmusic_library_corpus_track_artists",
        "Subscriptions" to "FEmusic_library_corpus_artists",
        "Podcasts" to "FEmusic_library_non_music_audio_list",
    )

    fun interface FeedCallback {
        fun onResult(json: String?, message: String?)
    }







    fun librarySongs(callback: FeedCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                Innertube.ensureSessionScope()
                if (Innertube.cookie == null) {
                    Innertube.checkSession(generation)
                    callback.onResult(
                        json.encodeToString(ListSerializer(Song.serializer()), emptyList()),
                        null,
                    )
                    return@launch
                }
                val songs = InnertubeParser.collectSongsDeep(Innertube.browse(LIBRARY_SONGS))
                    .distinctBy { it.videoId }
                Innertube.checkSession(generation)
                callback.onResult(json.encodeToString(ListSerializer(Song.serializer()), songs), null)
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }

    fun library(callback: FeedCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                Innertube.ensureSessionScope()
                if (Innertube.cookie == null) {
                    Innertube.checkSession(generation)
                    callback.onResult(
                        json.encodeToString(HomeFeed.serializer(), HomeFeed(emptyList())),
                        null,
                    )
                    return@launch
                }
                val shelves = coroutineScope {
                    FEEDS.map { (title, browseId) ->
                        async {
                            val items = InnertubeParser.parseLibraryItems(Innertube.browse(browseId))
                            HomeShelf(title, items)
                        }
                    }.awaitAll().filter { it.items.isNotEmpty() }
                }
                Innertube.checkSession(generation)
                callback.onResult(
                    json.encodeToString(HomeFeed.serializer(), HomeFeed(shelves)),
                    null,
                )
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }
}
