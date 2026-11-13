package com.bxane.nuvx.data.lyrics


internal const val MIN_GAP_MS = 4_000L





internal fun List<LyricLineDto>.withInstrumentalGaps(): List<LyricLineDto> {
    if (isEmpty()) return this
    val out = ArrayList<LyricLineDto>(size + 4)
    if (first().timeMs >= MIN_GAP_MS) out += LyricLineDto(0L, "")
    forEachIndexed { index, line ->
        out += line
        val next = getOrNull(index + 1) ?: return@forEachIndexed
        if (!line.hasKnownEnd) return@forEachIndexed
        val silence = next.timeMs - line.endMs
        if (silence >= MIN_GAP_MS && line.endMs > line.timeMs) out += LyricLineDto(line.endMs, "")
    }
    return out
}
