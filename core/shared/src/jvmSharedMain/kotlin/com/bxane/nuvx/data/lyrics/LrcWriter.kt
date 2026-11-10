package com.bxane.nuvx.data.lyrics

fun List<LyricLine>.toLrc(): String {
    if (isEmpty()) return ""
    if (none { it.timeMs > 0L }) {
        return joinToString("\n") { it.flattened() }
    }
    return sortedBy { it.timeMs }.joinToString("\n") { line -> stamp(line.timeMs) + line.flattened() }
}

private fun LyricLine.flattened(): String =
    background?.let { (text + " " + it.text).trim() } ?: text

const val WORD_LYRICS_FIELD = "NUVX_LYRICS"

fun List<LyricLine>.toEnhancedLrc(): String {
    if (isEmpty()) return ""
    if (none { it.isWordSynced || it.background?.isWordSynced == true }) return ""
    return sortedBy { it.timeMs }.joinToString("\n") { line -> stamp(line.timeMs) + line.enhancedBody() }
}

private const val ALIGNMENT_MARKER = "<R>"

private fun LyricLine.enhancedBody(): String {
    val runs = timedRuns()
    if (runs.isEmpty()) return flattened()
    val out = StringBuilder()
    if (alignment == LyricAlignment.End) out.append(ALIGNMENT_MARKER)
    var previous = timeMs
    runs.forEachIndexed { index, word ->
        if (word.syllables.isEmpty()) {
            val start = maxOf(word.startMs, previous)
            out.append(wordStamp(start)).append(word.text)
            previous = start
        } else {
            word.syllables.forEach { syllable ->
                val start = maxOf(syllable.startMs, previous)
                out.append(wordStamp(start)).append(word.text, syllable.startChar, syllable.endChar)
                previous = start
            }
        }
        if (index != runs.lastIndex) out.append(' ')
    }
    if (runs.any { it.syllables.isNotEmpty() }) out.append(' ')
    out.append(wordStamp(maxOf(runs.maxOf { it.endMs }, previous)))
    return out.toString()
}

private fun LyricLine.timedRuns(): List<LyricWord> {
    if (words.isEmpty()) return emptyList()
    val answer = background?.let { bg ->
        bg.words.ifEmpty {
            if (bg.text.isBlank()) emptyList() else listOf(LyricWord(bg.timeMs, bg.endMs, bg.text))
        }
    }.orEmpty()
    return words + answer
}

private fun stamp(timeMs: Long): String = "[" + clock(timeMs) + "]"

private fun wordStamp(timeMs: Long): String = "<" + clock(timeMs) + ">"

private fun clock(timeMs: Long): String {
    val total = timeMs.coerceAtLeast(0L)
    val minutes = (total / 60_000).toString().padStart(2, '0')
    val seconds = (total % 60_000 / 1_000).toString().padStart(2, '0')
    val centiseconds = (total % 1_000 / 10).toString().padStart(2, '0')
    return "$minutes:$seconds.$centiseconds"
}
