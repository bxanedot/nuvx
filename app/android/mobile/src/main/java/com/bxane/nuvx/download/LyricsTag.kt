package com.bxane.nuvx.download

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.lyrics.LyricsRepository
import com.bxane.nuvx.data.lyrics.toEnhancedLrc
import com.bxane.nuvx.data.lyrics.toLrc
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.durationMillis
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal object LyricsTag {

    private const val TAG = "nuvx"

    internal class Embeddable(val plain: String, val enhanced: String?)

    suspend fun forTrack(track: Song): Embeddable? {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        if (sources.isEmpty()) return null

        val durationMs = track.durationMillis()
        if (durationMs <= 0L) {
            Log.d(TAG, "no duration for ${track.videoId}; looking up lyrics by title and artist")
        }

        val found = try {
            withTimeoutOrNull(LOOKUP_MS) {
                LyricsRepository.lyrics(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = durationMs,
                    album = track.albumName,
                    sources = sources,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "no lyrics for ${track.videoId}: ${e.message}")
            return null
        } ?: return null

        if (found.lines.none { it.text.isNotBlank() }) return null

        val lrc = found.lines.toLrc()
        if (lrc.length > MAX_LRC_CHARS) {
            Log.w(TAG, "lyrics for ${track.videoId} are ${lrc.length} chars; not embedding")
            return null
        }
        if (lrc.isBlank()) return null
        val enhanced = found.lines.toEnhancedLrc().takeIf {
            it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2
        }
        Log.d(
            TAG,
            "embedding ${found.source.label} lyrics for ${track.videoId}" +
                if (enhanced != null) " (word-synced)" else "",
        )
        return Embeddable(plain = lrc, enhanced = enhanced)
    }

    private const val MAX_LRC_CHARS = 64_000

    private const val LOOKUP_MS = 30_000L
}
