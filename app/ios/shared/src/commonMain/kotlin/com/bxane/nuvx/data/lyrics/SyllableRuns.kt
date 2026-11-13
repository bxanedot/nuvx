package com.bxane.nuvx.data.lyrics





internal data class TimedRun(val startMs: Long, val endMs: Long, val raw: String)







internal fun wordsFromRuns(runs: List<TimedRun>): List<LyricWordDto> {
    val hasExplicitSpacing = runs.any { run -> run.raw.any(Char::isWhitespace) }
    val words = ArrayList<LyricWordDto>()
    val current = StringBuilder()
    var start = 0L
    var end = 0L

    fun flush() {
        if (current.isNotEmpty()) {
            words += LyricWordDto(start, maxOf(start, end), current.toString())
        }
        current.setLength(0)
        end = 0L
    }

    for (run in runs) {
        val content = run.raw.trim()
        if (content.isEmpty()) {
            flush()
            continue
        }
        val joinsPrevious = hasExplicitSpacing && current.isNotEmpty() &&
            !run.raw.first().isWhitespace() &&
            !current.last().isUnspacedScript() &&
            !content.first().isUnspacedScript()
        if (!joinsPrevious) flush()
        if (current.isEmpty()) start = run.startMs
        current.append(content)
        end = maxOf(end, run.endMs)
        if (run.raw.last().isWhitespace()) flush()
    }
    flush()
    return words
}

private fun Char.isUnspacedScript(): Boolean =
    this in '\u3400'..'\u9FFF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\u3000'..'\u303F' ||
        this in '\uFF00'..'\uFFEF' ||
        this in '\u0E00'..'\u0E7F'
