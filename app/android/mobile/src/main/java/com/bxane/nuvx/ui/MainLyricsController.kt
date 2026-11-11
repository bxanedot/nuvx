package com.bxane.nuvx.ui

import android.app.Application
import com.bxane.nuvx.data.lyrics.EmbeddedLyrics
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricsRepository
import com.bxane.nuvx.data.lyrics.LyricsSource
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class MainLyricsController(
    private val viewModelScope: CoroutineScope,
    private val getApplication: () -> Application,
) {    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    private var lyricsFor: List<Any>? = null

    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = lyricsLookupKey(
            videoId, sources, AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
        )
        if (lyricsFor == key) return
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            _lyrics.value = selected?.lines
            _lyricsSource.value = selected?.source
            _lyricsChecked.value = true
        }
    }

    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selectedLyricsSource == source) showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
    }
}
