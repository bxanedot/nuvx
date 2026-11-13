package com.bxane.nuvx.data.lyrics








object LyricFocus {









    const val SCROLL_LEAD_MIN_MS: Long = 350L








    const val SCROLL_LEAD_MAX_MS: Long = 500L

















    fun activeRows(lines: List<LyricLineDto>, positionMs: Long): List<Int> {






        if (!isSynced(lines)) return emptyList()
        val starts = startTimes(lines)
        val latest = starts.indexOfLast { it <= positionMs }
        if (latest < 0) return emptyList()
        return (0..latest).filter { index ->
            val line = lines[index]
            !line.isGap && (index == latest || (
                !line.isGap &&
                    (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
                    starts[index] <= positionMs && positionMs < line.endMs
                ))
        }
    }












    fun scrollLead(lines: List<LyricLineDto>, positionMs: Long): Long {
        val starts = startTimes(lines)
        val current = starts.indexOfLast { it <= positionMs }
        if (current < 0) return SCROLL_LEAD_MIN_MS
        if (current + 1 >= lines.size) return SCROLL_LEAD_MIN_MS
        val gap = starts[current + 1] - lines[current].endMs
        return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
    }








    fun leadRow(lines: List<LyricLineDto>, positionMs: Long): Int {
        val active = activeRows(lines, positionMs)
        if (active.isNotEmpty()) return active.first()
        if (!isSynced(lines)) return -1
        val starts = startTimes(lines)
        val reach = positionMs + scrollLead(lines, positionMs)
        return lines.indices.lastOrNull { !lines[it].isGap && starts[it] <= reach } ?: -1
    }


    fun isSynced(lines: List<LyricLineDto>): Boolean = lines.any { line ->
        line.timeMs > 0L || line.words.any { it.startMs > 0L || it.endMs > 0L } ||
            line.background?.let { background ->
                background.timeMs > 0L || background.words.any { it.startMs > 0L || it.endMs > 0L }
            } == true
    }













    fun startTimes(lines: List<LyricLineDto>): LongArray {
        val raw = LongArray(lines.size) { lineStartMs(lines[it]) }

        val known = BooleanArray(lines.size) { it == 0 || raw[it] > 0L }
        if (known.all { it }) return raw
        val starts = raw.copyOf()
        var previous = 0
        var index = 1
        while (index < lines.size) {
            if (known[index]) {
                previous = index
                index += 1
                continue
            }
            var next = index
            while (next < lines.size && !known[next]) next += 1
            val base = raw[previous]
            for (missing in index until next) {
                val steps = (missing - previous).toLong()
                starts[missing] = if (next < lines.size) {
                    base + (raw[next] - base).coerceAtLeast(0L) * steps / (next - previous)
                } else {
                    base + UNTIMED_LINE_MS * steps
                }
            }
            index = next
        }
        return starts
    }


    private const val UNTIMED_LINE_MS: Long = 4_000L


    private fun lineStartMs(line: LyricLineDto): Long = if (line.timeMs == 0L) {
        line.words.minOfOrNull { it.startMs }
            ?: line.background?.words?.minOfOrNull { it.startMs }
            ?: line.timeMs
    } else {
        line.timeMs
    }
}
