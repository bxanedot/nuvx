package com.bxane.nuvx.core.lyrics

import com.bxane.nuvx.core.LyricLine
import com.bxane.nuvx.core.LyricWord
import com.bxane.nuvx.core.Lyrics
import kotlin.math.roundToLong

/** Parses the absolute clock-time TTML subset documented by the enabled lyrics services. */
object Ttml {
    private const val MAX_DOCUMENT_LENGTH = 1_048_576
    private val forbidden = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)
    private val comment = Regex("""<!--[\s\S]*?-->""")
    private val paragraph = Regex("""<p\b([^>]*)>([\s\S]*?)</p\s*>""", RegexOption.IGNORE_CASE)
    private val token = Regex("""<(/?)([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*)>|([^<]+)""")
    private val attribute = Regex("""([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*("[^"]*"|'[^']*')""")
    private val entity = Regex("""&(#x[0-9A-Fa-f]+|#\d+|amp|lt|gt|quot|apos);""")
    private val clock = Regex("""^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)$""")
    private val shortClock = Regex("""^(\d+):(\d{1,2}(?:\.\d+)?)$""")

    private data class WordText(val startMs: Long, var text: String)
    private data class Span(val beginMs: Long?, val text: StringBuilder = StringBuilder(), var hasTimedChild: Boolean = false)

    fun parse(text: String, offsetMs: Long = 0): Lyrics.Synced? {
        if (text.length > MAX_DOCUMENT_LENGTH || forbidden.containsMatchIn(text)) return null
        val source = comment.replace(text, "")
        val paragraphs = paragraph.findAll(source).toList()
        if (paragraphs.isEmpty() || Regex("""<p\b""", RegexOption.IGNORE_CASE).findAll(source).count() != paragraphs.size ||
            Regex("""</p\s*>""", RegexOption.IGNORE_CASE).findAll(source).count() != paragraphs.size) return null

        val lines = paragraphs.map { match -> parseLine(match.groupValues[1], match.groupValues[2], offsetMs) ?: return null }
        return Lyrics.Synced(lines.sortedBy(LyricLine::startMs), wordSynced = lines.any { it.words.isNotEmpty() })
    }

    private fun parseLine(rawAttributes: String, body: String, offsetMs: Long): LyricLine? {
        val attributes = attributes(rawAttributes)
        val begin = attributes["begin"]?.let(::clockMs) ?: return null
        val shiftedBegin = addOffset(begin, offsetMs) ?: return null
        val tokens = token.findAll(body).toList()
        if (tokens.joinToString("") { it.value } != body) return null

        val plain = StringBuilder()
        val words = mutableListOf<WordText>()
        val spans = mutableListOf<Span>()
        for (match in tokens) {
            val tagName = match.groupValues[2].substringAfterLast(':').lowercase()
            if (tagName.isEmpty()) {
                val value = decodeXml(match.groupValues[4]) ?: return null
                plain.append(value)
                val timedSpan = spans.lastOrNull { it.beginMs != null }
                if (timedSpan != null) timedSpan.text.append(value)
                continue
            }

            if (tagName != "span") continue
            val closing = match.groupValues[1] == "/"
            if (closing) {
                if (spans.isEmpty()) return null
                val closed = spans.removeAt(spans.lastIndex)
                if (closed.beginMs != null) {
                    if (!closed.hasTimedChild && closed.text.isNotBlank()) {
                        val shifted = addOffset(closed.beginMs, offsetMs) ?: return null
                        words += WordText(shifted, closed.text.toString())
                    }
                    spans.lastOrNull { it.beginMs != null }?.hasTimedChild = true
                }
            } else {
                val attrs = attributes(match.groupValues[3])
                val rawBegin = attrs["begin"]
                val start = if (rawBegin == null) null else clockMs(rawBegin) ?: return null
                val span = Span(start)
                val selfClosing = match.groupValues[3].trimEnd().endsWith("/")
                if (selfClosing) {
                    if (start != null) spans.lastOrNull { it.beginMs != null }?.hasTimedChild = true
                } else spans += span
            }
        }
        if (spans.isNotEmpty()) return null

        val lineText = plain.toString().trim()
        if (lineText.isEmpty()) return null
        return LyricLine(shiftedBegin, lineText, words.map { LyricWord(it.text, it.startMs) })
    }

    private fun attributes(source: String): Map<String, String> = buildMap {
        attribute.findAll(source).forEach { match ->
            put(match.groupValues[1].substringAfterLast(':').lowercase(), match.groupValues[2].drop(1).dropLast(1))
        }
    }

    private fun clockMs(source: String): Long? {
        val value = source.trim()
        val full = clock.matchEntire(value)
        if (full != null) {
            val hours = full.groupValues[1].toLongOrNull() ?: return null
            val minutes = full.groupValues[2].toLongOrNull() ?: return null
            val seconds = full.groupValues[3].toDoubleOrNull() ?: return null
            if (minutes > 59 || seconds >= 60.0) return null
            val whole = multiply(hours, 3_600_000L) ?: return null
            val minuteMs = multiply(minutes, 60_000L) ?: return null
            val secondMs = (seconds * 1000.0).roundToLong()
            return add(whole, minuteMs)?.let { add(it, secondMs) }
        }

        val short = shortClock.matchEntire(value) ?: return null
        val minutes = short.groupValues[1].toLongOrNull() ?: return null
        val seconds = short.groupValues[2].toDoubleOrNull() ?: return null
        if (seconds >= 60.0) return null
        val minuteMs = multiply(minutes, 60_000L) ?: return null
        return add(minuteMs, (seconds * 1000.0).roundToLong())
    }

    private fun decodeXml(source: String): String? {
        if (entity.replace(source, "").contains('&')) return null
        var invalid = false
        return entity.replace(source) { match ->
            val value = match.groupValues[1]
            when (value) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (value.startsWith("#x")) value.drop(2).toIntOrNull(16) else value.drop(1).toIntOrNull()
                    if (code == null || code !in 0..0x10FFFF || code in 0xD800..0xDFFF) {
                        invalid = true
                        ""
                    } else if (code <= 0xFFFF) code.toChar().toString()
                    else {
                        val scalar = code - 0x10000
                        "${(0xD800 + scalar / 0x400).toChar()}${(0xDC00 + scalar % 0x400).toChar()}"
                    }
                }
            }
        }.takeUnless { invalid }
    }

    private fun addOffset(value: Long, offset: Long): Long? = add(value, offset)?.takeIf { it >= 0 }
    private fun multiply(value: Long, factor: Long): Long? = if (value > Long.MAX_VALUE / factor) null else value * factor
    private fun add(left: Long, right: Long): Long? =
        if ((right > 0 && left > Long.MAX_VALUE - right) || (right < 0 && left < Long.MIN_VALUE - right)) null else left + right
}
