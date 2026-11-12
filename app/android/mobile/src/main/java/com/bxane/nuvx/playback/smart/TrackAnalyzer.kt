// SPDX-License-Identifier: AGPL-3.0-or-later

package com.bxane.nuvx.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AutomixPerformanceMode
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import java.util.Locale

@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    private val tracker = BeatTracker(
        modelPath = {
            java.io.File(context.filesDir, BeatTracker.RETIRED_MODEL_ASSET).delete()
            unpackedAsset(BeatTracker.MODEL_ASSET)
        },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )
    private val vocals = VocalTracker(
        modelPath = { unpackedAsset(VocalTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
    )

    private val regionProcessor = TrackAnalysisRegionProcessor(
        tracker = tracker,
        vocals = vocals,
        stopped = { stopped },
        tag = TAG,
    )

    init {
        AnalysisLog.install { message, error -> Log.w(TAG, message, error) }
    }

    private fun unpackedAsset(name: String): String {
        val file = java.io.File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    private val stopped: Boolean get() = ListenTogether.state.value.inParty

    @Volatile private var thermalStatus = 0

    private val thermalWatch by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.let { power ->
                thermalStatus = power.currentThermalStatus
                power.addThermalStatusListener(Runnable::run) { thermalStatus = it }
            }
        }
    }

    private val overheated: Boolean
        get() {
            thermalWatch
            return thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }

    private val resolver by lazy { context.contentResolver }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val sourceAnalysisUris = ConcurrentHashMap<String, Uri>()
    private val sourceResolutions = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionAttempted = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val provisional = ConcurrentHashMap.newKeySet<String>()

    private val headAttempts = ConcurrentHashMap<String, Long>()

    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    private val shortDecodes = ConcurrentHashMap<String, Int>()

    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    private val store = AnalysisStore { context.filesDir }

    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    private val discarded = ConcurrentHashMap<String, Int>()

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvx-smart-analysis").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        if (!restoreAttempted.add(trackId)) return
        executor.execute {
            val stored = store.load(trackId) ?: return@execute
            Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
            results.putIfAbsent(trackId, stored)
        }
    }

    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    fun isAnalysing(trackId: String): Boolean = trackId in running

    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (stopped) return
        if (trackId in running) return
        if (overheated) {
            restoreOnce(trackId)
            return
        }
        val analysisUri = analysisUriFor(trackId, uri) ?: return

        restoreOnce(trackId)

        val local = LocalAudioSource.isLocal(analysisUri)

        val complete = if (local) {
            emptyList()
        } else {
            cache.renditionsOf(analysisUri)
                .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(analysisUri.getQueryParameter("v"), it.key) }
                .filter { it.isComplete && it.key !in badRenditions }
        }
        val usableComplete = local || complete.isNotEmpty()

        val recorded = results[trackId]
        val untried = if (local) {
            uri.toString() !in triedRenditions
        } else {
            complete.any { it.key !in triedRenditions }
        }
        val supersedable = when {
            recorded == null -> false
            trackId in provisional -> usableComplete
            else -> !recorded.isUsable && untried
        }
        if (recorded != null && !supersedable) {
            if (trackId in provisional && !usableComplete) cache.requestAnalysisHead(analysisUri)
            return
        }
        if (recorded != null && !recorded.isUsable) shortDecodes.remove(trackId)
        val headRendition = if (usableComplete) null else headWorthTrying(trackId, analysisUri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            cache.requestAnalysisHead(analysisUri)
            return
        }
        if (!running.add(trackId)) return

        executor.execute {
            try {
                Process.setThreadPriority(
                    if (AppSettings.automixPerformanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                        Process.THREAD_PRIORITY_BACKGROUND
                    } else {
                        Process.THREAD_PRIORITY_DEFAULT
                    },
                )
                val landed = results[trackId]
                if (landed != null && landed.isUsable && trackId !in provisional) return@execute
                if (stopped) return@execute
                if (usableComplete) {
                    val outcome = analyze(trackId, analysisUri, durationSeconds)
                    val whole = outcome.analysis
                    if (whole != null) {
                        results[trackId] = whole
                        provisional.remove(trackId)
                        shortDecodes.remove(trackId)
                        store.save(trackId, whole)
                        restoreAttempted.add(trackId)
                    } else if (outcome.decodedShort &&
                        shortDecodes.merge(trackId, 1, Int::plus)!! >= MAX_SHORT_DECODE_ATTEMPTS
                    ) {
                        Log.w(TAG, "Giving up on $trackId after $MAX_SHORT_DECODE_ATTEMPTS short decodes")
                        if (trackId !in provisional) {
                            results[trackId] = TrackAnalysis(
                                status = TrackAnalysis.STATUS_READY,
                                trackId = trackId,
                                duration = durationSeconds,
                            )
                        }
                    }
                } else {
                    analyzeHead(trackId, analysisUri, durationSeconds, headRendition!!)?.let { head ->
                        provisional.add(trackId)
                        results[trackId] = head
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Analysis of $trackId failed", error)
                if (usableComplete) {
                    results[trackId] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = trackId,
                        duration = durationSeconds,
                    )
                    provisional.remove(trackId)
                }
            } finally {
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    private fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val candidate = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    TAG,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    private fun analyzeHead(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected != null && rendition.key != cache.cacheKeyOf(uri)) {
            val length = openSource()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                Log.d(TAG, "Head rendition ${rendition.key} for $trackId has no readable duration yet")
                return null
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Head rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }

        val window = BeatTracker.WINDOW_SECONDS
        val head = regionProcessor.region(::openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: run {
                if (stopped) return null
                Log.d(TAG, "Head pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        if (head.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Head pass for $trackId decoded only ${"%.1f".format(Locale.ROOT, head.seconds)}s; too short")
            return null
        }
        val grid = head.grid
        val entry = head.features
        if (grid == null && entry == null) {
            Log.d(TAG, "Head pass for $trackId produced nothing usable")
            return null
        }

        Log.d(
            TAG,
            "Analysed head of $trackId: bpm=${grid?.bpm ?: entry?.bpm} " +
                "conf=${grid?.beatConfidence ?: entry?.beatConfidence} " +
                "audibleStart=${entry?.audibleStartTime} pickup=${entry?.pickupTime} " +
                "introEnd=${entry?.introEndTime} mixInCandidates=${entry?.mixInCandidates?.size ?: 0} " +
                "over ${"%.1f".format(Locale.ROOT, head.seconds)}s",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            bpm = grid?.bpm ?: entry?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: entry?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: entry?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: entry?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: entry?.firstBeat ?: 0.0,
            key = entry?.key.orEmpty(),
            keyConfidence = entry?.keyConfidence ?: 0.0,
            audibleStartTime = entry?.audibleStartTime,
            pickupTime = entry?.pickupTime,
            introEndTime = entry?.introEndTime ?: 0.0,
            mixInTime = entry?.mixInTime ?: 0.0,
            mixInCandidates = entry?.mixInCandidates.orEmpty(),
            headBeats = grid?.let { BeatSide(it.beats, it.downbeats, it.bpm, it.beatConfidence) },
        )
    }

    private fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val complete = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.isComplete && it.key !in badRenditions }
        if (complete.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            complete.firstOrNull { it.key == own }?.let { return it }
            return complete.singleOrNull()?.also {
                Log.d(
                    TAG,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in complete) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                badRenditions.add(candidate.key)
                continue
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(candidate.key)
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    TAG,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    private class Copy(
        val key: String,
        val rendition: AudioCache.Rendition?,
        val open: () -> MediaDataSource?,
    )

    private fun copyToRead(trackId: String, uri: Uri, durationSeconds: Double): Copy? {
        if (LocalAudioSource.isLocal(uri)) {
            return Copy(key = uri.toString(), rendition = null) { LocalAudioSource.open(resolver, uri) }
        }
        val rendition = chooseRendition(trackId, uri, durationSeconds) ?: return null
        return Copy(key = rendition.key, rendition = rendition) { cache.renditionDataSource(uri, rendition) }
    }

    private fun analysisUriFor(trackId: String, playbackUri: Uri): Uri? {
        if (playbackUri.getQueryParameter("v") != null || playbackUri.authority != "source") return playbackUri
        if (SourceResolver.isPickedAddonRow(playbackUri)) return null
        sourceAnalysisUris[trackId]?.let { return it }
        if (trackId in sourceResolutionAttempted) return null
        if (!sourceResolutions.add(trackId)) return null
        val target = SourceResolver.targetIn(playbackUri)
        sourceResolutionScope.launch {
            try {
                val match = findYouTubeMatch(target)
                if (match != null) {
                    sourceAnalysisUris[trackId] = Uri.parse(AutomixAnalysisSource.opusUri(match.videoId))
                    Log.d(TAG, "Automix analysis for $trackId will use YouTube Opus ${match.videoId}")
                } else {
                    Log.d(TAG, "No YouTube match for Automix analysis of $trackId")
                }
            } catch (error: Throwable) {
                Log.d(TAG, "YouTube match failed for Automix analysis of $trackId: ${error.message}")
            } finally {
                sourceResolutionAttempted.add(trackId)
                sourceResolutions.remove(trackId)
            }
        }
        return null
    }

    private suspend fun findYouTubeMatch(target: TrackMatcher.Target): com.bxane.nuvx.data.model.Song? {
        if (target.title.isBlank()) return null
        for (query in TrackMatcher.queries(target)) {
            val candidates = YtMusicRepository.search(query, SearchFilter.SONGS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { return it }
        }
        return null
    }

    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false)

    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
    )

    private fun structure(
        trackId: String,
        uri: Uri,
        copy: Copy,
        effectiveDuration: Double,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = copy.open()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) { stopped } }
            ?: return Structural(null)
        val (pcm, _) = decoded

        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    if (copy.rendition != null) "container — cached with holes?" else "container — truncated file?",
            )
            copy.rendition?.let { rendition ->
                badRenditions.add(rendition.key)
                if (rendition.isComplete && discardsOf(rendition.key) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    discarded.merge(rendition.key, 1, Int::plus)
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            }
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        return Structural(TrackFeatures.analyze(samples, effectiveDuration))
    }

    private fun analyze(trackId: String, uri: Uri, durationSeconds: Double): WholeTrack {
        val copy = copyToRead(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        val structural = structure(trackId, uri, copy, effectiveDuration)
        if (stopped) return WholeTrack(null)
        if (structural.decodedShort) return WholeTrack(null, decodedShort = true)
        val features = structural.features ?: return WholeTrack(empty(trackId, effectiveDuration))

        val span = regionProcessor.modelWindowSeconds()
        val headEnd = regionProcessor.headEnd(effectiveDuration, span)
        val tailStart = regionProcessor.tailStart(effectiveDuration, span)
        val regions = ArrayList<TrackAnalysisRegionProcessor.Region>()
        for ((from, to) in regionProcessor.modelWindows(headEnd, tailStart, effectiveDuration, span)) {
            if (stopped) return WholeTrack(null)
            regionProcessor.region(openSource, from, to, features)?.let(regions::add)
        }
        if (stopped) return WholeTrack(null)

        val beats = regionProcessor.seamBeats(regions)
        val headSide = regionProcessor.beatSide(beats, 0.0, headEnd)
        val tailSide = regionProcessor.beatSide(beats, tailStart, effectiveDuration)
        val headGrid = headSide?.let(regionProcessor::beatGrid)
        val tailGrid = tailSide?.let(regionProcessor::beatGrid)

        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "grid=${if (leading != null) "model" else "dsp"} " +
                "beats=${headGrid?.beats?.size ?: 0}+${tailGrid?.beats?.size ?: 0} " +
                "downbeats=${headGrid?.downbeats?.size ?: 0}+${tailGrid?.downbeats?.size ?: 0} " +
                "vocalMask=${if (regions.any { it.vocalMask != null }) "model" else "dsp"} " +
                "windows=${regions.size} vocalWindows=${regions.count { it.vocalWindow != null }}",
        )

        return WholeTrack(
                TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = effectiveDuration,
                    contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
                    bpm = leading?.bpm ?: features.bpm,
                beatInterval = leading?.beatInterval ?: features.beatInterval,
                beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
                downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                    .ifEmpty { features.downbeats }
                    .sorted(),
                firstBeat = headGrid?.firstBeat ?: features.firstBeat,
                phraseBoundaries = features.phraseBoundaries,
                key = features.key,
                keyConfidence = features.keyConfidence,
                audibleStartTime = features.audibleStartTime,
                pickupTime = features.pickupTime,
                introEndTime = features.introEndTime,
                outroStartTime = features.outroStartTime,
                mixInTime = features.mixInTime,
                mixOutTime = features.mixOutTime,
                mixInCandidates = features.mixInCandidates,
                mixOutCandidates = features.mixOutCandidates,
                energyCurve = features.energyCurve,
                lowEnergyCurve = features.lowEnergyCurve,
                vocalActivityMask = regionProcessor.mergeMasks(features.energyCurve.size, regions.mapNotNull { it.vocalMask })
                    ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale),
                vocalProbability = features.vocalProbability,
                headBeats = headSide,
                tailBeats = tailSide,
                vocalWindows = regions.mapNotNull { it.vocalWindow },
                levelWindows = regions.mapNotNull { it.levelWindow },
            ),
        )
    }

    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    fun loopRegion(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        sampleRate: Int,
    ): com.bxane.nuvx.playback.loopmix.LoopRegion? {
        if (uri.getQueryParameter("v") == null && uri.authority == "source") return null
        val began = SystemClock.elapsedRealtime()
        val copy = runCatching { copyToRead(trackId, uri, durationSeconds) }.getOrNull() ?: return null
        val (stereo, start) = copy.open()?.use { source ->
            AudioDecoder.decodeRegionStereo(source, max(0.0, fromSeconds), toSeconds) { stopped }
        } ?: return null
        if (stereo.left.isEmpty()) return null
        val rate = sampleRate.toDouble()
        val left: FloatArray
        val right: FloatArray
        if (abs(stereo.sampleRate - rate) > 0.5) {
            left = MelSpectrogram.resample(stereo.left, stereo.sampleRate, rate) ?: return null
            right = MelSpectrogram.resample(stereo.right, stereo.sampleRate, rate) ?: return null
        } else {
            left = stereo.left
            right = stereo.right
        }
        Log.d(
            TAG,
            "Loop region $trackId from ${copy.key}: asked ${"%.2f".format(Locale.ROOT, fromSeconds)}s, " +
                "got ${"%.3f".format(Locale.ROOT, start)}s + ${"%.2f".format(Locale.ROOT, left.size / rate)}s " +
                "at ${stereo.sampleRate.toInt()}->$sampleRate Hz in ${SystemClock.elapsedRealtime() - began}ms",
        )
        return com.bxane.nuvx.playback.loopmix.LoopRegion(left, right, start)
    }

    fun mixWaveform(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        bins: Int,
    ): MixWaveform.Bands? {
        val analysisUri = if (uri.getQueryParameter("v") != null || uri.authority != "source") {
            uri
        } else {
            sourceAnalysisUris[trackId] ?: return null
        }
        val began = SystemClock.elapsedRealtime()
        val copy = runCatching { copyToRead(trackId, analysisUri, durationSeconds) }.getOrNull() ?: return null
        val chosen = SystemClock.elapsedRealtime()
        val bands = copy.open()?.use { source ->
            MixWaveform.build(source, fromSeconds, toSeconds, bins) { stopped }
        }
        Log.d(
            TAG,
            "Mix waveform $trackId: copy ${chosen - began}ms, decode ${SystemClock.elapsedRealtime() - chosen}ms, " +
                "asked ${"%.1f".format(Locale.ROOT, fromSeconds)}s, decoder started " +
                "${bands?.decodedFrom?.let { "%.1f".format(Locale.ROOT, it) } ?: "-"}s",
        )
        return bands
    }

    fun release() {
        executor.shutdownNow()
        sourceResolutionScope.cancel()
        sourceAnalysisUris.clear()
        sourceResolutions.clear()
        sourceResolutionAttempted.clear()
        headAttempts.clear()
        headSkipLogged.clear()
        provisional.clear()
        restoreAttempted.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        discarded.clear()
        tracker.release()
        vocals.release()
    }

    private companion object {
        const val TAG = "NuvxTrackAnalyzer"

        const val HEAD_BYTES_MARGIN = 1.35

        const val MIN_HEAD_BYTES = 256L * 1024L

        const val MIN_DECODED_FRACTION = 0.95

        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        const val MAX_RENDITION_DISCARDS = 1

        const val RENDITION_DURATION_TOLERANCE = 1.0

        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        const val MIN_HEAD_SECONDS = 12.0

        const val HEAD_RETRY_GROWTH = 2
    }
}
