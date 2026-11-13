

package com.bxane.nuvx.data

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.VersionTimeMap
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal object YtMusicVersionRepository {
    private const val TAG = "nuvx"
    private val audioVersionCache = ConcurrentHashMap<String, Song>()
    private val videoVersionCache = ConcurrentHashMap<String, Song?>()
    private val versionTimeMaps = ConcurrentHashMap<Pair<String, String>, VersionTimeMap>()

    fun cachedAudioVersion(videoId: String): Song? = audioVersionCache[videoId]
    fun cachedVideoVersion(videoId: String): Song? = videoVersionCache[videoId]

    fun rememberVideoVersion(audioId: String, video: Song) {
        videoVersionCache[audioId] = video
    }

    fun versionTimeMap(fromId: String, toId: String): VersionTimeMap? = versionTimeMaps[fromId to toId]

    suspend fun resolveAudio(song: Song): Song {
        if (!song.isVideo) return song
        audioVersionCache[song.videoId]?.let { return it }
        officialVersion(song, wantAudio = true)?.let { audio ->
            audioVersionCache[song.videoId] = audio
            return audio
        }
        val target = TrackMatcher.targetOf(song)
        var searchFailed = false
        for (query in TrackMatcher.queries(target)) {
            val candidates = YtMusicRepository.search(query, SearchFilter.SONGS)
                .onFailure { searchFailed = true }
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { match ->
                Log.d(TAG, "audio switch: '${song.title}' -> '${match.title}' ($query)")
                audioVersionCache[song.videoId] = match
                return match
            }
            TrackMatcher.bestOfficialAudioForVideo(candidates, target)?.let { match ->
                Log.d(TAG, "audio switch: accepted video/runtime drift '${song.title}' -> '${match.title}' ($query)")
                audioVersionCache[song.videoId] = match
                return match
            }
        }
        Log.w(TAG, "audio switch: no official song match for '${song.title}' by '${song.artist}'")
        if (!searchFailed) audioVersionCache[song.videoId] = song
        return song
    }

    suspend fun resolveVideo(song: Song): Song? {
        if (song.isVideo) return null
        videoVersionCache[song.videoId]?.let { return it }
        officialVersion(song, wantAudio = false)?.let { video ->
            videoVersionCache[song.videoId] = video
            return video
        }
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val candidates = YtMusicRepository.search(query, SearchFilter.VIDEOS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { match ->
                Log.d(TAG, "video switch: '${song.title}' -> '${match.title}' ($query)")
                videoVersionCache[song.videoId] = match
                return match
            }
        }
        Log.w(TAG, "video switch: no video match for '${song.title}' by '${song.artist}'")
        videoVersionCache[song.videoId] = null
        return null
    }

    private suspend fun officialVersion(song: Song, wantAudio: Boolean): Song? {
        if (Innertube.cookie == null) return null
        val pair = try {
            InnertubeParser.parseVersionPair(Innertube.next(song.videoId), song.videoId, wantAudio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "version pair lookup failed for '${song.title}': ${e.message}")
            null
        } ?: run {
            Log.d(TAG, "version pair: YouTube pairs nothing with '${song.title}'")
            return null
        }
        pair.timeMap?.let { map ->
            versionTimeMaps[song.videoId to pair.song.videoId] = map
            versionTimeMaps[pair.song.videoId to song.videoId] = map.inverted()
        }
        Log.d(
            TAG,
            "version pair: '${song.title}' -> '${pair.song.title}' (${pair.song.videoId}), " +
                "segments=${pair.timeMap?.segments?.size ?: 0}",
        )
        return pair.song
    }
}
