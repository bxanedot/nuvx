package com.bxane.nuvx.data

import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object TrackLog {
    @Volatile
    var echo: ((level: Char, tag: String, message: String, error: Throwable?) -> Unit)? = null

    @Volatile
    var sourcesLine: () -> String? = { null }

    @Volatile
    var headerLines: () -> List<String> = { emptyList() }


    fun d(tag: String, message: String, about: String? = working.get()) {
        echo?.invoke('D', tag, message, null)
        record('D', message, about)
    }

    fun i(tag: String, message: String, about: String? = working.get()) {
        echo?.invoke('I', tag, message, null)
        record('I', message, about)
    }

    fun w(tag: String, message: String, about: String? = working.get()) {
        echo?.invoke('W', tag, message, null)
        record('W', message, about)
    }

    fun w(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        echo?.invoke('W', tag, message, error)
        record('W', "$message\n${error.stackTraceToString()}", about)
    }

    fun e(tag: String, message: String, about: String? = working.get()) {
        echo?.invoke('E', tag, message, null)
        record('E', message, about)
    }

    fun e(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        echo?.invoke('E', tag, message, error)
        record('E', "$message\n${error.stackTraceToString()}", about)
    }

    private val working = ThreadLocal<String?>()

    fun about(id: String?): CoroutineContext = working.asContextElement(id)

    private class Line(val at: Long, val level: Char, val text: String, val track: String?)

    private val lines = ArrayDeque<Line>()

    private var held = 0

    private fun record(level: Char, message: String, about: String?) {
        val text = if (message.length > MAX_LINE_CHARS) {
            message.take(MAX_LINE_CHARS) + "…(${message.length - MAX_LINE_CHARS} more)"
        } else {
            message
        }
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), level, text, about))
            held += text.length
            while (held > MAX_HELD_CHARS && lines.isNotEmpty()) {
                held -= lines.removeFirst().text.length
            }
        }
    }

    private val startedAt = ConcurrentHashMap<String, Long>()

    fun onTrackStarted(videoId: String) {
        if (startedAt.size >= MAX_REMEMBERED) startedAt.clear()
        startedAt[videoId] = System.currentTimeMillis()
    }

    suspend fun forTrack(song: Song, stats: NerdStats.Snapshot?): String = withContext(Dispatchers.Default) {
        val held = synchronized(lines) { lines.toList() }
        val from = listOfNotNull(
            held.firstOrNull { it.track == song.videoId }?.at,
            startedAt[song.videoId]?.minus(LEAD_IN_MS),
        ).minOrNull()
        val since = held.filter { from == null || it.at >= from }
        val window = since.filter { it.track == null || it.track == song.videoId }
        header(song, stats, from, window.size, since.size - window.size) + "\n" +
            window.joinToString("\n") { "${CLOCK.format(Date(it.at))} ${it.level} ${it.text}" } +
            "\n"
    }

    private fun header(
        song: Song,
        stats: NerdStats.Snapshot?,
        from: Long?,
        count: Int,
        elsewhere: Int,
    ) = buildString {
        appendLine("nuvx log — ${song.title} — ${song.artist}")
        appendLine("id=${song.videoId} duration=${song.durationText ?: "?"} album=${song.albumName ?: "?"}")
        appendLine("playing: ${stats.describe()}")
        sourcesLine()?.let(::appendLine)
        appendLine(
            "window: ${from?.let { CLOCK.format(Date(it)) } ?: "everything held"} → " +
                "${CLOCK.format(Date())} ($count lines" +
                (if (elsewhere > 0) ", $elsewhere for other tracks left out)" else ")"),
        )
        headerLines().forEach(::appendLine)
    }

    private fun NerdStats.Snapshot?.describe(): String {
        if (this == null) return "nothing reported"
        val measured = listOfNotNull(
            mimeType,
            bitDepth?.let { "$it-bit" },
            bitrateKbps?.let { "$it kbps" },
            sampleRateHz?.let { "$it Hz" },
            channels?.let { "${it}ch" },
        ).joinToString(" · ").ifEmpty { "nothing reported" }
        val promised = claimed?.summary?.let { " (source said: $it)" }.orEmpty()
        val tier = when {
            isHiRes -> " [Hi-Res Lossless]"
            isLossless -> " [Lossless]"
            isHiQuality -> " [Hi-Quality]"
            else -> ""
        }
        return measured + promised + tier
    }

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private const val LEAD_IN_MS = 20_000L

    private const val MAX_HELD_CHARS = 512_000

    private const val MAX_LINE_CHARS = 2_000

    private const val MAX_REMEMBERED = 32
}
