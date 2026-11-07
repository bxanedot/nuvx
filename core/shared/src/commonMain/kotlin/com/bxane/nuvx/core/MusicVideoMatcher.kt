package com.bxane.nuvx.core

import kotlin.math.abs

/** Conservative catalog matching for an explicit song-to-video switch; metadata does not verify upload-channel ownership. */
object MusicVideoMatcher {
    private val versionTerms = listOf("live", "remix", "acoustic", "cover", "tribute", "instrumental", "extended", "sped up", "slowed")
    private val videoSuffix = Regex("""[\s\-–—|:]*[\(\[]?\s*(?:official\s+)?(?:music\s+video|lyric\s+video|video|visualizer|visualiser)\s*[\)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val titleSeparators = Regex("[^a-z0-9]+")

    fun youtubeWatchUrl(track: Track): String? =
        track.playbackVideoId.takeIf { track.mediaKind == MediaKind.VIDEO && it.matches(VIDEO_ID) }
            ?.let { "https://www.youtube.com/watch?v=$it" }

    fun bestMatch(song: Track, candidates: List<Track>): Track? {
        val songArtistId = song.artistId?.takeIf(String::isNotBlank)
        if (song.isLocal || song.mediaKind != MediaKind.SONG || song.artists.isEmpty() && songArtistId == null) return null
        val title = coreTitle(song.title).takeIf { it.isNotEmpty() } ?: return null
        val duration = song.durationMs?.takeIf { it > 0 } ?: return null
        val artists = song.artists.map(::normalize).filter(String::isNotEmpty).toSet()
        return candidates.asSequence()
            .filter { it.mediaKind == MediaKind.VIDEO && it.videoId.matches(VIDEO_ID) }
            .filter { coreTitle(it.title) == title && version(it.title) == version(song.title) }
            .filter { candidate ->
                val candidateArtistId = candidate.artistId?.takeIf(String::isNotBlank)
                if (songArtistId != null && candidateArtistId != null) songArtistId == candidateArtistId
                else candidate.artists.any { normalize(it) in artists }
            }
            .mapNotNull { candidate ->
                val candidateDuration = candidate.durationMs?.takeIf { it > 0 } ?: return@mapNotNull null
                val delta = abs(candidateDuration - duration)
                if (delta > maxOf(30_000L, duration / 10)) null else candidate to delta
            }
            .minWithOrNull(compareBy<Pair<Track, Long>> { videoTitlePriority(it.first.title) }.thenBy { it.second })
            ?.first
    }

    private fun coreTitle(value: String): String {
        var title = value.trim()
        while (videoSuffix.containsMatchIn(title)) title = title.replace(videoSuffix, "").trim()
        return normalize(title)
    }

    private fun version(value: String): Set<String> = versionTerms.filterTo(mutableSetOf()) { term ->
        Regex("(^|[^a-z0-9])${Regex.escape(term)}([^a-z0-9]|$)").containsMatchIn(value.lowercase())
    }

    private fun videoTitlePriority(value: String): Int {
        val words = " ${value.lowercase().replace(titleSeparators, " ").trim()} "
        return when {
            " official music video " in words -> 0
            " official video " in words -> 1
            " music video " in words -> 2
            " lyric video " in words || " visualizer " in words || " visualiser " in words || " official audio " in words -> 4
            else -> 3
        }
    }

    private fun normalize(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}

class MusicVideoResolver(private val router: BackendRouter) {
    suspend fun find(track: Track): Track? {
        if (track.isLocal || track.mediaKind != MediaKind.SONG || track.artistLine.isBlank()) return null
        return MusicVideoMatcher.bestMatch(track, router.searchVideos("${track.title} ${track.artistLine}").tracks)
    }
}
