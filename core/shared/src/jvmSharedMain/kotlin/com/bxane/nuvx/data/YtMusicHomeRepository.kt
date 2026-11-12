// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.data

import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.HomeFeed
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import java.util.concurrent.ConcurrentHashMap

internal object YtMusicHomeRepository {
    private val moodGenreShelfCache = ConcurrentHashMap<String, List<HomeShelf>>()

    suspend fun home(): Result<HomeFeed> = YtMusicRepository.call("home") {
        val home = Innertube.browse("FEmusic_home")
        HomeFeed(InnertubeParser.parseHome(home), InnertubeParser.continuationToken(home))
    }

    suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>> = YtMusicRepository.call("home:$browseId") {
        shelvesOf(browseId)
    }

    suspend fun moreHome(token: String): Result<HomeFeed> = YtMusicRepository.call("home:more") {
        val response = Innertube.browseContinuation(token)
        HomeFeed(
            shelves = InnertubeParser.parseHomeContinuation(response),
            continuation = InnertubeParser.continuationToken(response),
        )
    }

    suspend fun quickPicks(excludeSongIds: Set<String>): Result<List<Song>> = YtMusicRepository.call("quickPicks") {
        val homeRaw = runCatching { Innertube.browse("FEmusic_home") }.getOrNull()
        val shelves = if (homeRaw != null) InnertubeParser.parseHome(homeRaw) else emptyList()

        val qpShelf = shelves.firstOrNull {
            it.title.contains("quick", ignoreCase = true) ||
                it.title.contains("pick", ignoreCase = true) ||
                it.title.contains("mix", ignoreCase = true) ||
                it.title.contains("recommend", ignoreCase = true)
        }
        val qpSongs = qpShelf?.items?.mapNotNull { item ->
            item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                Song(
                    videoId = vid,
                    title = item.title,
                    artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
        }.orEmpty()
        if (qpSongs.isNotEmpty()) return@call qpSongs

        val candidateShelves = if (shelves.size > 1) {
            shelves.drop(1).filterNot {
                it.title.contains("recent", ignoreCase = true) ||
                    it.title.contains("history", ignoreCase = true) ||
                    it.title.contains("listen again", ignoreCase = true)
            }
        } else shelves

        val homeShelfSongs = candidateShelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        if (homeShelfSongs.isNotEmpty()) return@call homeShelfSongs

        val allHomeTrackSongs = shelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        val uniqueAllHome = allHomeTrackSongs.filterNot { it.videoId in excludeSongIds }
        if (uniqueAllHome.isNotEmpty()) return@call uniqueAllHome

        val explore = runCatching { shelvesOf("FEmusic_new_releases") }.getOrDefault(emptyList())
        val exploreSongs = explore.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        if (exploreSongs.isNotEmpty()) return@call exploreSongs

        allHomeTrackSongs.ifEmpty { exploreSongs }
    }

    suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = YtMusicRepository.call("moods-and-genres") {
        InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
    }

    suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>> {
        val key = "$browseId:${params.orEmpty()}"
        moodGenreShelfCache[key]?.let { return Result.success(it) }
        return YtMusicRepository.call("mood-genre:$browseId") {
            InnertubeParser.parseHome(Innertube.browse(browseId, params))
        }.also { result -> result.getOrNull()?.let { moodGenreShelfCache.putIfAbsent(key, it) } }
    }

    suspend fun podcasts(): Result<List<HomeShelf>> = YtMusicRepository.call("podcasts") {
        val params = InnertubeParser.podcastsChipParams(Innertube.browse("FEmusic_home"))
            ?: error("YouTube Music offered no Podcasts")
        InnertubeParser.parseHome(Innertube.browse("FEmusic_home", params))
    }

    suspend fun moodGenreArtwork(browseId: String, params: String?): Result<String?> =
        moodGenreShelves(browseId, params).map { shelves ->
            shelves.asSequence().flatMap { it.items.asSequence() }
                .mapNotNull(ShelfItem::thumbnailUrl)
                .firstOrNull()
        }

    private suspend fun shelvesOf(browseId: String): List<HomeShelf> =
        InnertubeParser.parseHome(Innertube.browse(browseId))
}
