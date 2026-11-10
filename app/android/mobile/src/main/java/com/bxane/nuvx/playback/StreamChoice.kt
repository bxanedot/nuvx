package com.bxane.nuvx.playback

import android.os.SystemClock
import com.bxane.nuvx.data.sources.SourceStream
import java.util.concurrent.ConcurrentHashMap

object StreamChoice {

    private class Choice(val stream: SourceStream, val at: Long, val substituted: Boolean)

    private val chosen = ConcurrentHashMap<String, Choice>()

    fun of(videoId: String): SourceStream? {
        val choice = chosen[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - choice.at > TTL_MS) {
            chosen.remove(videoId)
            return null
        }
        return choice.stream
    }

    fun remember(videoId: String, stream: SourceStream, substituted: Boolean) {
        if (chosen.size >= MAX_REMEMBERED) {
            val now = SystemClock.elapsedRealtime()
            chosen.entries.removeIf { now - it.value.at > TTL_MS }
            if (chosen.size >= MAX_REMEMBERED) {
                chosen.minByOrNull { it.value.at }?.let { chosen.remove(it.key) }
            }
        }
        chosen[videoId] = Choice(stream, SystemClock.elapsedRealtime(), substituted)
    }

    fun isSubstitute(videoId: String): Boolean = chosen[videoId]?.substituted == true

    fun isYouTube(videoId: String): Boolean = chosen[videoId]?.substituted == false

    fun forget(videoId: String) {
        chosen.remove(videoId)
    }

    fun forgetWhere(keep: Set<String>, drop: (SourceStream) -> Boolean) {
        chosen.entries.removeIf { (videoId, choice) -> videoId !in keep && drop(choice.stream) }
    }

    private val refusedSubstitutes = ConcurrentHashMap<String, Long>()

    fun refuseSubstitutes(videoId: String) {
        if (refusedSubstitutes.size >= MAX_REMEMBERED) refusedSubstitutes.clear()
        refusedSubstitutes[videoId] = SystemClock.elapsedRealtime()
    }

    fun substitutesRefused(videoId: String): Boolean {
        val at = refusedSubstitutes[videoId] ?: return false
        if (SystemClock.elapsedRealtime() - at <= REFUSAL_MS) return true
        refusedSubstitutes.remove(videoId)
        return false
    }

    private const val TTL_MS = 15 * 60 * 1000L

    private const val REFUSAL_MS = 10 * 60 * 1000L

    private const val MAX_REMEMBERED = 32
}
