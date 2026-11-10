package com.bxane.nuvx.data.model

class VersionTimeMap(segments: List<Segment>) {

    data class Segment(val fromStartMs: Long, val toStartMs: Long, val durationMs: Long)

    val segments: List<Segment> = segments.sortedBy { it.fromStartMs }

    init {
        require(this.segments.isNotEmpty()) { "a time map needs at least one segment" }
    }

    fun map(positionMs: Long): Long {
        for (segment in segments) {
            if (positionMs < segment.fromStartMs) return segment.toStartMs
            val into = positionMs - segment.fromStartMs
            if (into < segment.durationMs) return segment.toStartMs + into
        }
        val last = segments.last()
        return last.toStartMs + last.durationMs
    }

    fun inverted(): VersionTimeMap =
        VersionTimeMap(segments.map { Segment(it.toStartMs, it.fromStartMs, it.durationMs) })
}

data class VersionPair(val song: Song, val timeMap: VersionTimeMap?)
