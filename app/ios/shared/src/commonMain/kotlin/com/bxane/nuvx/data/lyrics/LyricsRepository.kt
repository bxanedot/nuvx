package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.DebugLog
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull










































object LyricsRepository {


    data class Result(val source: LyricsSource, val lines: List<LyricLineDto>)














    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = true,
        isrc: String? = null,

        onSourceStarted: (suspend (LyricsSource) -> Unit)? = null,




        onSourceResult: (suspend (LyricsSource, Result?) -> Unit)? = null,

        onSourceCancelled: (suspend (LyricsSource) -> Unit)? = null,

        onSourceBlocked: (suspend (LyricsSource) -> Unit)? = null,
    ): Result? = coroutineScope {
        val configuredSequence = order.filter { it in sources } +
            LyricsSource.entries.filter { it in sources && it !in order }
        val sequence = identityAwareOrder(configuredSequence, videoId)





        val searchTitle = title.forLyricsSearch(artist)
        val searchArtist = artist.artistForLyricsSearch()




        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        val hit = if (known == null) {
            identify(videoId, searchTitle, searchArtist, durationMs, album, sequence)
        } else {
            null
        }
        val recording = known ?: hit?.isrc?.takeIf { it.isNotBlank() }
        if (recording != null) {
            DebugLog.d("lyrics: recording is $recording")
        }

        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            source to async(Dispatchers.Default) {
                onSourceStarted?.invoke(source)
                try {
                    val found = fetch(
                        source, videoId, searchTitle, searchArtist, durationMs, album,
                        recording, hit,
                    )?.let { rawLines ->
                        val lines = LyricsMatching.normalize(rawLines.withBackgroundVocals())
                        lines.takeIf { LyricsMatching.hasPlausibleDuration(it, durationMs) }
                            ?.let { Result(source, it) }
                            ?: run {
                                DebugLog.d("${source.name} returned an implausible lyric timeline; ignoring it")
                                null
                            }
                    }
                    onSourceResult?.invoke(source, found)
                    found
                } catch (cancelled: CancellationException) {
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (blocked: SimpMusicBlockedException) {
                    DebugLog.d("${source.name} is blocked for third-party apps")
                    onSourceBlocked?.invoke(source)
                    null
                } catch (e: Exception) {
                    DebugLog.d("${source.name} failed: ${e.message}")
                    onSourceResult?.invoke(source, null)
                    null
                }
            }
        }

        try {
            if (!prioritizeSyllableSync) {
                for ((_, job) in racing) {
                    val found = runCatching { job.await() }.getOrNull() ?: continue
                    return@coroutineScope found
                }
                return@coroutineScope null
            }

            val exactVideoSources = racing.filter { (source, _) -> source in VIDEO_ID_SOURCES }
            val catalogSources = racing.filterNot { (source, _) -> source in VIDEO_ID_SOURCES }
            var exactVideoLineSynced: Result? = null
            var exactVideoUnsynced: Result? = null
            for ((_, job) in exactVideoSources) {
                val found = runCatching { job.await() }.getOrNull() ?: continue
                if (found.lines.any { it.isWordSynced }) return@coroutineScope found
                if (found.lines.any(::hasAnyTiming)) {
                    if (exactVideoLineSynced == null) exactVideoLineSynced = found
                } else if (exactVideoUnsynced == null) {
                    exactVideoUnsynced = found
                }
            }



            exactVideoLineSynced?.let { return@coroutineScope it }

            var catalogTimedFallback: Result? = null
            var catalogUnsyncedFallback: Result? = null
            for ((_, job) in catalogSources) {
                val found = runCatching { job.await() }.getOrNull() ?: continue
                if (found.lines.any { it.isWordSynced }) {
                    val exactText = exactVideoUnsynced
                    if (exactText != null && !LyricsMatching.documentsAgree(exactText.lines, found.lines)) {
                        DebugLog.d("ignoring title-matched timed lyrics: words disagree with exact-video lyrics")
                        continue
                    }
                    return@coroutineScope found
                }
                if (found.lines.any(::hasAnyTiming)) {
                    val exactText = exactVideoUnsynced
                    if (catalogTimedFallback == null &&
                        (exactText == null || LyricsMatching.documentsAgree(exactText.lines, found.lines))
                    ) {
                        catalogTimedFallback = found
                    }
                } else if (catalogUnsyncedFallback == null) {
                    catalogUnsyncedFallback = found
                }
            }

            catalogTimedFallback ?: exactVideoUnsynced ?: catalogUnsyncedFallback
        } finally {


            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,

        hit: BiniLyrics.Hit?,
    ): List<LyricLineDto>? = when (source) {
        LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
        LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
        LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
        LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc ?: hit?.isrc)
        LyricsSource.BINI_LYRICS ->
            (hit?.let { BiniLyrics.lyricsFor(it) }
                ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc))
                ?.also { remember(videoId, it.isrc) }
                ?.lines
        LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
        LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
        LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
        LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
        LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
        LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
        LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
        LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
        LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
        LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)


        LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
        LyricsSource.GENIUS -> Genius.lyrics(title, artist)
    }

    private fun hasAnyTiming(line: LyricLineDto): Boolean =
        line.timeMs > 0L || line.words.any { it.startMs > 0L || it.endMs > 0L } ||
            line.background?.let(::hasAnyTiming) == true

    internal fun identityAwareOrder(sequence: List<LyricsSource>, videoId: String): List<LyricsSource> {
        if (!isVideoId(videoId)) return sequence
        return sequence.filter { it in VIDEO_ID_SOURCES } + sequence.filterNot { it in VIDEO_ID_SOURCES }
    }

    private val VIDEO_ID_SOURCES = setOf(
        LyricsSource.SIMP_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.YOUTUBE_MUSIC,
    )
















    private const val IDENTIFY_TIMEOUT_MS = 2_500L









    private suspend fun identify(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
    ): BiniLyrics.Hit? {
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        if (!title.isUsableLyricsQuery()) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                runCatching { BiniLyrics.identify(title, artist, durationMs, album) }.getOrNull()
            }
        }
        if (hit == null) return null
        remember(videoId, hit.isrc)
        return hit
    }


    private const val REMEMBERED = 100









    private val isrcs = BoundedLru<String, String>(REMEMBERED)

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs[videoId] = isrc
    }


    internal fun rememberedIsrc(videoId: String): String? = isrcs[videoId]

    internal fun forget() = isrcs.clear()
}
















@OptIn(ExperimentalAtomicApi::class)
internal class BoundedLru<K : Any, V : Any>(private val capacity: Int) {

    private val ref = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? {
        val current = ref.load()
        val found = current[key] ?: return null


        if (current.keys.lastOrNull() == key) return found
        val next = LinkedHashMap(current)
        next.remove(key)
        next[key] = found
        ref.compareAndSet(current, next)
        return found
    }

    operator fun set(key: K, value: V) {
        while (true) {
            val current = ref.load()
            val next = LinkedHashMap(current)
            next.remove(key)
            next[key] = value
            while (next.size > capacity) {
                val coldest = next.keys.firstOrNull() ?: break
                next.remove(coldest)
            }
            if (ref.compareAndSet(current, next)) return
        }
    }

    fun clear() = ref.store(emptyMap())

    val size: Int get() = ref.load().size
}
