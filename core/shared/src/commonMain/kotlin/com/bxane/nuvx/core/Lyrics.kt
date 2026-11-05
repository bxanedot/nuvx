package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** [startMs] of a word comes only from the source; words never get invented timestamps. */
data class LyricWord(val text: String, val startMs: Long)

data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord> = emptyList())

sealed interface Lyrics {
    data class Plain(val text: String) : Lyrics
    /** [wordSynced] is true only when the source supplied per-word times. */
    data class Synced(val lines: List<LyricLine>, val wordSynced: Boolean) : Lyrics {
        /** Index of the line at [positionMs], or -1 before the first line. */
        fun lineAt(positionMs: Long): Int = lines.indexOfLast { it.startMs <= positionMs }

        /**
         * How far word [word] of line [line] has been sung, 0..1, from its own source timestamp to the next word's
         * (or the next line's for the last word; [LAST_WORD_MS] if there is no next line). Timing is never invented.
         */
        fun wordProgress(line: Int, word: Int, positionMs: Long): Float {
            val words = lines.getOrNull(line)?.words ?: return 0f
            val start = words.getOrNull(word)?.startMs ?: return 0f
            val end = words.getOrNull(word + 1)?.startMs ?: lines.getOrNull(line + 1)?.startMs ?: (start + LAST_WORD_MS)
            if (end <= start) return if (positionMs >= start) 1f else 0f
            return ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        }

        private companion object { const val LAST_WORD_MS = 600L }
    }
    data object Instrumental : Lyrics
}

object Lrc {
    private val lineTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** Parses LRC and enhanced LRC (`<mm:ss.xx>` word tags). [offsetMs] is the user's timing correction. */
    fun parse(text: String, offsetMs: Long = 0): Lyrics.Synced? {
        val fileOffset = Regex("""\[offset:([+-]?\d+)]""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val shift = offsetMs + fileOffset
        val lines = text.lineSequence().flatMap { raw ->
            // Leading run of [mm:ss.xx] tags; one line may carry several (repeated chorus).
            val stamps = generateSequence(lineTag.matchAt(raw, 0)) { lineTag.matchAt(raw, it.range.last + 1) }.toList()
            if (stamps.isEmpty()) return@flatMap emptySequence()
            val body = raw.substring(stamps.last().range.last + 1)
            val tags = wordTag.findAll(body).toList()
            val words = tags.mapIndexedNotNull { i, m ->
                val end = tags.getOrNull(i + 1)?.range?.first ?: body.length
                body.substring(m.range.last + 1, end).takeIf { it.isNotBlank() }?.let { LyricWord(it, ms(m) + shift) }
            }
            val plain = wordTag.replace(body, "").trim()
            stamps.asSequence().map { LyricLine(ms(it) + shift, plain, words) }
        }.sortedBy { it.startMs }.toList()
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, lines.any { it.words.isNotEmpty() })
    }

    private fun ms(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return (min.toLong() * 60 + sec.toLong()) * 1000 + f
    }
}

/** One lyrics source. Returns null when it has nothing for [track]; may throw on network errors (the chain skips it). */
interface LyricsProvider {
    /** Shown to the user as the lyrics credit. */
    val name: String
    /** Where the credit links to; set it when a provider's terms ask for a link wherever its lyrics appear. */
    val homepage: String? get() = null
    suspend fun find(track: Track, offsetMs: Long = 0): Lyrics?
}

data class SourcedLyrics(val lyrics: Lyrics, val source: String, val link: String? = null)

/** Asks the preferred provider first when selected; otherwise timed lyrics win over earlier plain text. Failures are skipped. */
class LyricsChain(private val providers: List<LyricsProvider>) {
    private data class LookupKey(
        val provider: String,
        val videoId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val mediaKind: MediaKind,
        val pairedVideoId: String?,
        val offsetMs: Long,
    )

    private data class CachedLyrics(val lyrics: Lyrics, val createdAt: TimeMark)

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<LookupKey, CachedLyrics>()
    private val inFlight = mutableMapOf<LookupKey, CompletableDeferred<Lyrics?>>()

    val providerNames: List<String> get() = providers.map { it.name }

    suspend fun find(
        track: Track,
        offsetMs: Long = 0,
        preferredSource: String? = null,
        providerOrder: List<String> = providerNames,
        disabledSources: Set<String> = emptySet(),
        preferWordSynced: Boolean = true,
    ): SourcedLyrics? {
        val known = providers.associateBy { it.name }
        val order = providerOrder.filter { it in known }.distinct() + providerNames.filterNot(providerOrder.toSet()::contains)
        val active = order.filterNot(disabledSources::contains).mapNotNull(known::get)
        var fallback: SourcedLyrics? = null
        val preferred = active.firstOrNull { it.name == preferredSource }
        val ordered = preferred?.let { selected -> listOf(selected) + active.filterNot { it === selected } } ?: active
        for (p in ordered) {
            val found = try { findCached(p, track, offsetMs) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            if (found == Lyrics.Instrumental || found is Lyrics.Synced && (!preferWordSynced || found.wordSynced)) {
                return SourcedLyrics(found, p.name, p.homepage)
            }
            if (fallback == null ||
                (fallback.source != preferred?.name && fallback.lyrics is Lyrics.Plain && found is Lyrics.Synced)
            ) {
                fallback = SourcedLyrics(found, p.name, p.homepage)
            }
        }
        return fallback
    }

    private suspend fun findCached(provider: LyricsProvider, track: Track, offsetMs: Long): Lyrics? {
        val key = LookupKey(
            provider.name, track.videoId, track.title, track.artists, track.album, track.durationMs,
            track.mediaKind, track.pairedVideoId, offsetMs,
        )
        var request: CompletableDeferred<Lyrics?>? = null
        var owner = false
        val cached = cacheMutex.withLock {
            cache[key]?.let { entry ->
                if (entry.createdAt.elapsedNow() < CACHE_TTL) {
                    cache.remove(key)
                    cache[key] = entry
                    return@withLock entry.lyrics
                }
                cache.remove(key)
            }
            request = inFlight[key]
            if (request == null) {
                request = CompletableDeferred()
                inFlight[key] = request
                owner = true
            }
            null
        }
        if (cached != null) return cached
        val sharedRequest = request!!
        if (!owner) return sharedRequest.await()

        try {
            val result = provider.find(track, offsetMs)
            cacheMutex.withLock {
                if (result != null) {
                    cache[key] = CachedLyrics(result, TimeSource.Monotonic.markNow())
                    while (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
                }
                inFlight.remove(key)
                sharedRequest.complete(result)
            }
            return result
        } catch (failure: Throwable) {
            cacheMutex.withLock {
                inFlight.remove(key)
                sharedRequest.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private companion object {
        const val CACHE_LIMIT = 128
        val CACHE_TTL = 6.hours
    }
}

/** LRCLIB (https://lrclib.net) — free, keyless; asks clients to identify themselves via User-Agent. */
class LrcLib(private val http: HttpClient) : LyricsProvider {
    override val name = "LRCLIB"
    override val homepage = "https://lrclib.net"

    @Serializable
    private class Hit(val instrumental: Boolean = false, val plainLyrics: String? = null, val syncedLyrics: String? = null, val duration: Double? = null)

    /** Exact match first; YouTube titles often carry extras ("/ B-side", "(Official Video)"), so fall back to a cleaned search. */
    override suspend fun find(track: Track, offsetMs: Long): Lyrics? {
        val artist = track.artists.firstOrNull().orEmpty()
        val exact = http.get("https://lrclib.net/api/get") {
            header("User-Agent", UA)
            parameter("track_name", track.title)
            parameter("artist_name", artist)
            track.album?.let { parameter("album_name", it) }
            track.durationMs?.let { parameter("duration", it / 1000) }
        }
        val hit = if (exact.status != HttpStatusCode.NotFound) exact.body<Hit>() else {
            val candidates = http.get("https://lrclib.net/api/search") {
                header("User-Agent", UA)
                parameter("track_name", cleanTitle(track.title))
                if (artist.isNotEmpty()) parameter("artist_name", artist)
            }.body<List<Hit>>()
            pick(candidates, track.durationMs) ?: return null
        }
        return when {
            hit.instrumental -> Lyrics.Instrumental
            hit.syncedLyrics != null -> Lrc.parse(hit.syncedLyrics, offsetMs)
            hit.plainLyrics != null -> Lyrics.Plain(hit.plainLyrics)
            else -> null
        }
    }

    companion object {
        private const val UA = "nuvx (https://nuvx.lol)"
        private val extras = Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hq|hd|4k|explicit|clean)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        /** "One More Time / Aerodynamic (Official Video) [HQ]" → "One More Time". */
        internal fun cleanTitle(title: String) = extras.replace(title, "").substringBefore(" / ").trim().ifEmpty { title }

        /** Synced lyrics within 3 s of our duration win; a wrong-length version would drift out of sync, so it isn't used for timing. */
        private fun pick(hits: List<Hit>, durationMs: Long?): Hit? {
            val fits = { h: Hit -> durationMs == null || h.duration == null || kotlin.math.abs(h.duration * 1000 - durationMs) <= 3_000 }
            return hits.firstOrNull { it.syncedLyrics != null && fits(it) }
                ?: hits.firstOrNull { it.instrumental && fits(it) }
                ?: hits.firstOrNull { it.plainLyrics != null }?.let { Hit(plainLyrics = it.plainLyrics) }
        }
    }
}
