package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.lyrics.PaxSenix
import com.bxane.nuvx.data.lyrics.normalizePaxSenixApiKey
import kotlinx.coroutines.flow.MutableStateFlow

internal const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"

internal class LyricsSettingsController(
    private val minOffsetMs: Int,
    private val maxOffsetMs: Int,
) {
    private lateinit var prefs: SharedPreferences

    val lyricsBlur = MutableStateFlow(true)
    val lyricsOffsetMs = MutableStateFlow(0)
    val translationLanguage = MutableStateFlow("")
    val syncedLyrics = MutableStateFlow(true)
    val lyricsSources = MutableStateFlow(LyricsSource.offered.toSet())
    val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)
    val prioritizeSyllableSync = MutableStateFlow(true)
    val paxSenixApiKey = MutableStateFlow("")

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun readPresentationPreferences() {
        lyricsBlur.value = prefs.getBoolean(KEY_LYRICS_BLUR, true)
        lyricsOffsetMs.value = prefs.getInt(KEY_LYRICS_OFFSET_MS, 0).coerceIn(minOffsetMs, maxOffsetMs)
        translationLanguage.value = prefs.getString(KEY_TRANSLATION_LANGUAGE, "").orEmpty()
    }

    fun readProviderPreferences() {
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        lyricsSources.value = readLyricsSources()
        lyricsSourceOrder.value = readLyricsSourceOrder()
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, true)
        paxSenixApiKey.value = prefs.getString(KEY_PAXSENIX_API_KEY, "").orEmpty()
        PaxSenix.setApiKey(paxSenixApiKey.value)
    }

    fun setLyricsBlur(value: Boolean) {
        lyricsBlur.value = value
        prefs.edit().putBoolean(KEY_LYRICS_BLUR, value).apply()
    }

    fun setLyricsOffsetMs(value: Int) {
        val normalized = value.coerceIn(minOffsetMs, maxOffsetMs)
        if (lyricsOffsetMs.value == normalized) return
        lyricsOffsetMs.value = normalized
        prefs.edit().putInt(KEY_LYRICS_OFFSET_MS, normalized).apply()
    }

    fun setTranslationLanguage(value: String) {
        translationLanguage.value = value
        prefs.edit().putString(KEY_TRANSLATION_LANGUAGE, value).apply()
    }

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.edit().putBoolean(KEY_SYNCED_LYRICS, value).apply()
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        lyricsSources.value = value
        prefs.edit()
            .putString(KEY_LYRICS_SOURCES, value.joinToString(",") { it.name })
            .putString(KEY_LYRICS_SOURCES_SEEN, LyricsSource.offered.joinToString(",") { it.name })
            .apply()
    }

    fun setLyricsSourceOrder(value: List<LyricsSource>) {
        lyricsSourceOrder.value = value
        prefs.edit().putString(KEY_LYRICS_SOURCE_ORDER, value.joinToString(",") { it.name }).apply()
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, value).apply()
    }

    fun setPaxSenixApiKey(value: String) {
        val normalized = normalizePaxSenixApiKey(value)
        paxSenixApiKey.value = normalized
        prefs.edit().putString(KEY_PAXSENIX_API_KEY, normalized).apply()
        PaxSenix.setApiKey(normalized)
    }

    fun resetLyricsSourceSettings() {
        setLyricsSources(LyricsSource.offered.toSet())
        setLyricsSourceOrder(LyricsSource.offered)
        setPrioritizeSyllableSync(true)
    }

    private fun readLyricsSources(): Set<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCES, null)
            ?: return LyricsSource.offered.toSet()
        val chosen = stored.split(",").toSources()
        val seen = prefs.getString(KEY_LYRICS_SOURCES_SEEN, null)
            ?.split(",")?.toSources()
            ?: LEGACY_SOURCES
        return (chosen + LyricsSource.entries.filter { it !in seen }).filterNot { it.hidden }.toSet()
    }

    private fun List<String>.toSources(): Set<LyricsSource> =
        mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }.toSet()

    private fun readLyricsSourceOrder(): List<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCE_ORDER, null)
            ?: return LyricsSource.offered
        val saved = stored.split(",")
            .mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
        return LyricsSource.ordered(saved)
    }

    private val LEGACY_SOURCES = setOf(
        LyricsSource.LYRICS_PLUS,
        LyricsSource.PAXSENIX,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.SIMP_MUSIC,
        LyricsSource.KUGOU,
        LyricsSource.LRCLIB,
        LyricsSource.MUSIXMATCH,
        LyricsSource.GENIUS,
    )

    private companion object {
        const val KEY_LYRICS_BLUR = "lyrics_blur"
        const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
        const val KEY_TRANSLATION_LANGUAGE = "translation_language"
        const val KEY_SYNCED_LYRICS = "synced_lyrics"
        const val KEY_LYRICS_SOURCES = "lyrics_sources"
        const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
        const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
        const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    }
}
