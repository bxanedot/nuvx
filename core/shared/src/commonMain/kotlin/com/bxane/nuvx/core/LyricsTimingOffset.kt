package com.bxane.nuvx.core

/** Per-recording lyric timing corrections, keyed by the media ID currently playing. */
object LyricsTimingOffset {
    const val MAX_OFFSET_MS = 30_000L
    const val STEP_MS = 100L
    private const val KEY_PREFIX = "lyricsTimingOffset."

    fun read(store: KeyValueStore, track: Track?): Long {
        val id = track?.playbackVideoId?.takeIf(String::isNotBlank) ?: return 0L
        return store.get(KEY_PREFIX + id)?.toLongOrNull()?.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS) ?: 0L
    }

    fun write(store: KeyValueStore, track: Track?, offsetMs: Long): Long {
        val id = track?.playbackVideoId?.takeIf(String::isNotBlank) ?: return 0L
        val bounded = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        store.put(KEY_PREFIX + id, bounded.takeIf { it != 0L }?.toString())
        return bounded
    }

    fun shift(lyrics: Lyrics.Synced, offsetMs: Long): Lyrics.Synced {
        if (offsetMs == 0L) return lyrics
        return lyrics.copy(lines = lyrics.lines.map { line ->
            line.copy(
                startMs = addSaturated(line.startMs, offsetMs),
                words = line.words.map { word -> word.copy(startMs = addSaturated(word.startMs, offsetMs)) },
            )
        })
    }

    private fun addSaturated(timeMs: Long, offsetMs: Long): Long = when {
        offsetMs > 0 && timeMs > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs < 0 && timeMs < Long.MIN_VALUE - offsetMs -> Long.MIN_VALUE
        else -> timeMs + offsetMs
    }
}
