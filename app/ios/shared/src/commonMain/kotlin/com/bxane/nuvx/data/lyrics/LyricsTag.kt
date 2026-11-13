package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.withTimeoutOrNull







internal object LyricsTag {

    class Embeddable(val plain: String, val enhanced: String?)

    suspend fun forTrack(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): Embeddable? {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSourcesSet()
        } else {
            emptySet()
        }
        if (sources.isEmpty()) return null





        val found = runCatching {
            withTimeoutOrNull(LOOKUP_MS) {
                LyricsRepository.lyrics(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    album = album,
                    sources = sources,
                    order = AppSettings.lyricsSourceOrderList(),
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
            }
        }.getOrNull() ?: return null

        if (found.lines.none { it.text.isNotBlank() }) return null

        val lrc = found.lines.toLrc()
        if (lrc.length > MAX_LRC_CHARS) return null
        if (lrc.isBlank()) return null
        val enhanced = found.lines.toEnhancedLrc().takeIf {
            it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2
        }
        return Embeddable(plain = lrc, enhanced = enhanced)
    }

    private const val MAX_LRC_CHARS = 64_000
    private const val LOOKUP_MS = 15_000L
}
