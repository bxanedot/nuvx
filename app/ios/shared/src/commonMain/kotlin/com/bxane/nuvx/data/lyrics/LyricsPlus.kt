package com.bxane.nuvx.data.lyrics

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable




object LyricsPlus {
    private val MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",





        "https://lyricsplus.atomix.one",
    )








    private const val HEDGE_DELAY_MS = 750L










    private val health = MirrorHealth()



















    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
    ): List<LyricLineDto>? = coroutineScope {




        val hosts = health.order(MIRRORS)

        val pending = mutableListOf<Pair<String, Deferred<FetchOutcome>>>()
        var nextHost = 0
        fun startNext(): Boolean {
            if (nextHost >= hosts.size) return false
            val host = hosts[nextHost++]
            pending += host to async { fetch(host, title, artist, durationMs, album, isrc) }
            return true
        }
        startNext()

        try {
            while (pending.isNotEmpty() || nextHost < hosts.size) {
                if (pending.isEmpty()) {
                    startNext()
                    continue
                }
                val finished: Pair<String, FetchOutcome>? = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                    if (nextHost < hosts.size) onTimeout(HEDGE_DELAY_MS) { null }
                }
                if (finished == null) {


                    startNext()
                    continue
                }
                val (host, outcome) = finished
                pending.removeAll { it.first == host }



                when (outcome) {
                    is FetchOutcome.Unreachable -> {
                        health.unreachable(host)


                        startNext()
                    }
                    is FetchOutcome.Answered -> {
                        health.answered(host)
                        if (outcome.lines.isNotEmpty()) return@coroutineScope outcome.lines


                        while (startNext()) {   }
                    }
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }



    private sealed interface FetchOutcome {
        data class Answered(val lines: List<LyricLineDto>) : FetchOutcome
        data object Unreachable : FetchOutcome
    }

    private suspend fun fetch(
        host: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
    ): FetchOutcome {
        val query = buildMap {
            put("title", title)
            put("artist", artist)
            val seconds = durationMs / 1000
            if (seconds > 0) put("duration", seconds.toString())
            if (!album.isNullOrBlank()) put("album", album)



            if (!isrc.isNullOrBlank()) put("isrc", isrc)
        }
        val attempt = lyricsAttempt("$host/v2/lyrics/get", query = query)




        if (attempt !is LyricsAttempt.Answered) return FetchOutcome.Unreachable
        val response = runCatching {
            lyricsJson.decodeFromString(Response.serializer(), attempt.body)
        }.getOrNull() ?: return FetchOutcome.Answered(emptyList())
        return FetchOutcome.Answered(parse(response))
    }

    internal fun parse(response: Response): List<LyricLineDto> =
        response.lyrics.orEmpty().mapNotNull { line ->
            val start = line.time ?: return@mapNotNull null
            val words = mergeSyllables(line.syllabus.orEmpty())
            when {
                words.isNotEmpty() -> LyricLineDto(
                    timeMs = minOf(start, words.first().startMs),
                    text = words.joinToString(" ") { it.text },
                    words = words,
                )
                !line.text.isNullOrBlank() -> LyricLineDto(
                    timeMs = start,
                    text = line.text.trim(),
                    sungUntilMs = line.duration?.takeIf { it > 0 }?.let { start + it },
                )
                else -> null
            }
        }.sortedBy { it.timeMs }.withInstrumentalGaps()

    private fun mergeSyllables(syllables: List<Syllable>): List<LyricWordDto> {
        val words = mutableListOf<LyricWordDto>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L

        syllables.forEach { syllable ->
            val text = syllable.text ?: return@forEach
            if (text.isBlank()) return@forEach
            val time = syllable.time ?: return@forEach
            if (current.isEmpty()) start = time
            current.append(text.trim())
            end = time + (syllable.duration ?: 0L)
            if (text.last().isWhitespace()) {
                words += LyricWordDto(start, end, current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words += LyricWordDto(start, end, current.toString())
        return words
    }

    @Serializable
    internal data class Response(
        val type: String? = null,
        val lyrics: List<Line>? = null,
    )

    @Serializable
    internal data class Line(
        val time: Long? = null,
        val duration: Long? = null,
        val text: String? = null,
        @SerialName("syllabus") val syllabus: List<Syllable>? = null,
    )

    @Serializable
    internal data class Syllable(
        val time: Long? = null,
        val duration: Long? = null,
        val text: String? = null,
    )
}
