package com.licht.nuvx

import com.licht.nuvx.data.lyrics.LyricLine
import com.licht.nuvx.ui.player.activeLyricRows
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFocusTest {
    private val overlap = listOf(
        LyricLine(1_000, "upper", sungUntilMs = 4_000),
        LyricLine(2_000, "middle", sungUntilMs = 5_000),
        LyricLine(3_000, "lower", sungUntilMs = 6_000),
    )

    @Test fun upperLineKeepsAnchorAcrossThreeOverlappingVocals() {
        assertEquals(listOf(0, 1, 2), activeLyricRows(overlap, 3_500))
    }

    @Test fun anchorAdvancesExactlyWhenUpperVocalEnds() {
        assertEquals(listOf(1, 2), activeLyricRows(overlap, 4_000))
        assertEquals(listOf(2), activeLyricRows(overlap, 5_000))
    }

    @Test fun finishedMiddleRowDoesNotCutOffLongUpperVocal() {
        val lines = overlap.toMutableList()
        lines[1] = lines[1].copy(sungUntilMs = 2_900)
        assertEquals(listOf(0, 2), activeLyricRows(lines, 3_500))
    }

    @Test fun backgroundVocalKeepsItsParentRowAnchored() {
        val lines = listOf(
            LyricLine(1_000, "lead", sungUntilMs = 1_900,
                background = LyricLine(1_500, "echo", sungUntilMs = 3_000)),
            LyricLine(2_000, "next", sungUntilMs = 4_000),
        )
        assertEquals(listOf(0, 1), activeLyricRows(lines, 2_500))
        assertEquals(listOf(1), activeLyricRows(lines, 3_000))
    }

    @Test fun plainLrcAdvancesWithoutInventingAnOverlap() {
        val lines = overlap.map { it.copy(sungUntilMs = null) }
        assertEquals(listOf(1), activeLyricRows(lines, 2_500))
    }

    @Test fun firstAndLastLineBoundariesRemainStableAcrossSeeks() {
        val lines = listOf(
            LyricLine(1_000, "first", sungUntilMs = 1_800),
            LyricLine(2_000, "middle", sungUntilMs = 2_800),
            LyricLine(3_000, "last", sungUntilMs = 3_900),
        )
        assertEquals(emptyList<Int>(), activeLyricRows(lines, 999))
        assertEquals(listOf(0), activeLyricRows(lines, 1_000))
        assertEquals(listOf(1), activeLyricRows(lines, 2_000))
        assertEquals(listOf(2), activeLyricRows(lines, 3_000))
        assertEquals(listOf(2), activeLyricRows(lines, 4_500), "last line stays selected after its end")
        assertEquals(listOf(0), activeLyricRows(lines, 1_100), "backwards seeking must reset focus")
        assertEquals(listOf(2), activeLyricRows(lines, 3_500), "forwards seeking must select last line")
    }

    @Test fun emptyUntimedAndSameTimestampLyricsHaveDeterministicFocus() {
        assertEquals(emptyList<Int>(), activeLyricRows(emptyList(), 1_000))
        val untimed = listOf(LyricLine(0, "first"), LyricLine(0, "second"))
        assertEquals(listOf(1), activeLyricRows(untimed, 0))
        assertEquals(listOf(1), activeLyricRows(untimed, 10_000))
        val mixed = listOf(
            LyricLine(0, "unknown"), LyricLine(0, "other"),
            LyricLine(2_000, "known"),
        )
        assertEquals(listOf(1), activeLyricRows(mixed, 1_000))
        assertEquals(listOf(2), activeLyricRows(mixed, 2_000))
    }

    @Test fun backwardSeekRecomputesTheAnchor() {
        assertEquals(listOf(2), activeLyricRows(overlap, 5_500))
        assertEquals(listOf(0), activeLyricRows(overlap, 1_500))
        assertEquals(emptyList<Int>(), activeLyricRows(overlap, 500))
    }
}
