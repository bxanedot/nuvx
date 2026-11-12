package com.bxane.nuvx.data.sources

import android.net.Uri
import android.util.Log
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.playback.StreamContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

object SourceResolver {

    private const val TAG = "nuvx"

    fun requestForNow(): StreamRequest {
        val ceiling = AppSettings.effectiveAudioQuality
        return when {
            ceiling == AudioQuality.LOSSLESS -> StreamRequest.Lossless
            ceiling.maxKbps == Int.MAX_VALUE -> StreamRequest.Best
            else -> StreamRequest.Capped(ceiling.maxKbps)
        }
    }

    fun requestForDownload(
        quality: DownloadQuality = AppSettings.downloadQuality.value,
    ): StreamRequest = when {
        quality.keepsLossless -> StreamRequest.Lossless
        quality.maxKbps == Int.MAX_VALUE -> StreamRequest.Best
        else -> StreamRequest.Capped(quality.maxKbps)
    }

    suspend fun resolve(uri: Uri): SourceStream? {
        val configId = uri.getQueryParameter("s") ?: return null
        val trackId = uri.getQueryParameter("t") ?: return null
        return resolve(
            configId = configId,
            trackId = trackId,
            target = targetIn(uri),
            pickedMix = uri.getQueryParameter(MIX_PARAM),
        )
    }

    const val MIX_PARAM = "mix"
    const val MIX_STEREO = "stereo"
    const val MIX_ATMOS = "atmos"
    const val MIX_ANY = "any"

    fun isPickedAddonRow(uri: Uri): Boolean =
        uri.authority == "source" && uri.getQueryParameter(MIX_PARAM) != null

    fun mixOf(sourceQuality: String?): String = when (sourceQuality) {
        null -> MIX_ANY
        ModuleSource.DOLBY -> MIX_ATMOS
        else -> MIX_STEREO
    }

    suspend fun forDownloadOfRow(
        configId: String,
        trackId: String,
        sourceQuality: String?,
        request: StreamRequest = requestForDownload(),
    ): SourceStream? = SourceDownloadResolver.forDownloadOfRow(configId, trackId, sourceQuality, request)

    fun targetIn(uri: Uri) = TrackMatcher.Target(
        title = uri.getQueryParameter("n").orEmpty(),
        artist = uri.getQueryParameter("a").orEmpty(),
        durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
        album = uri.getQueryParameter("l"),
        isExplicit = uri.getQueryParameter("e")?.let { it == "1" },
        isVideo = uri.getQueryParameter("m") == "1",
    )

    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
        pickedMix: String? = null,
    ): SourceStream? {
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        val pinned = active.firstOrNull { it.configId == configId }

        val picked = pickedMix != null && pinned is AddonSource
        if (!picked && request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue
                if (upgraded.format.isLossless != true) continue
                TrackLog.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) {
                if (picked) {
                    (pinned as AddonSource).stream(trackId, request, stereoOnly = pickedMix == MIX_STEREO)
                } else {
                    pinned.stream(trackId, request)
                }
            }
                ?.let { return it.copy(sourceConfigId = pinned.configId) }
        }

        val fallbacks = active.filterNot {
            it.configId == configId || (pickedMix != null && it.kind == SourceKind.YOUTUBE)
        }
        val (fallbackSource, stream) = bestAcross(fallbacks, target, request) ?: return null
        TrackLog.d(TAG, "fallback: '${target.title}' served by ${fallbackSource.displayName}")
        return stream
    }

    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val request = requestForNow()
        val (source, stream) = bestAcross(rankedAbove(youtube.configId, active), target, request)
            ?: return null
        TrackLog.d(
            TAG,
            "substituted: '${target.title}' served by ${source.displayName} over YouTube" +
                " at ${stream.format.summary}" + if (stream.belowRequest) " (below request)" else "",
        )
        return stream
    }

    fun directAddons(): List<MusicSource> {
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return emptyList()
        return rankedAbove(youtube.configId, active).filter { it.kind == SourceKind.ADDON }
    }

    suspend fun directStream(
        target: TrackMatcher.Target,
        lengthOf: (suspend () -> Int?)? = null,
    ): SourceStream? = coroutineScope {
        if (target.title.isBlank() || target.isVideo) return@coroutineScope null
        val addons = directAddons()
        if (addons.isEmpty()) return@coroutineScope null
        val length = lengthOf?.takeIf { target.durationSec == null }?.let { fetch ->
            async {
                try {
                    fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val found = try {
            bestAcross(
                addons,
                target,
                requestForNow(),
                strictLength = true,
                requireSharedArtist = target.artist.isNotBlank(),
                driftSec = upgradeSlackSec(),
                lengthLater = length?.let { pending -> { pending.await() } },
                checkFileLength = true,
            )
        } finally {
            length?.cancel()
        }
        val (source, stream) = found ?: return@coroutineScope null
        TrackLog.d(
            TAG,
            "direct: '${target.title}' from ${source.displayName} at ${stream.format.summary}" +
                if (stream.belowRequest) " (below request)" else "",
        )
        stream
    }

    suspend fun youtubeFallback(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val youtube = SourceRegistry.activeForPlayback()
            .firstOrNull { it.kind == SourceKind.YOUTUBE }
            ?: return null
        return matchAndStream(
            source = youtube,
            target = target,
            request = StreamRequest.Best,
            strictLength = target.durationSec != null,
            requireSharedArtist = true,
        )?.also {
            TrackLog.d(TAG, "YouTube fallback matched '${target.title}' after its higher-quality source failed")
        }
    }

    suspend fun prefetchSubstitute(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val quick = rankedAbove(youtube.configId, active).filter { it.kind.worthPrefetching }
        if (quick.isEmpty()) return null
        val (source, stream) = bestAcross(quick, target, requestForNow()) ?: return null
        TrackLog.d(
            TAG,
            "warmed: '${target.title}' from ${source.displayName} at ${stream.format.summary}",
        )
        return stream
    }

    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val (source, chosen) = bestAcross(
            rankedAbove(youtube.configId, active).filterNot { it.configId == servedBy },
            target,
            request,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = upgradeSlackSec(),
        ) { candidate, stream ->
            worthSwapping(stream.format, playing).also { worth ->
                if (!worth) {
                    TrackLog.d(
                        TAG,
                        "${candidate.displayName}'s ${stream.format.summary} isn't worth swapping " +
                            "'${target.title}' off ${playing?.summary ?: "an unmeasured stream"}",
                    )
                }
            }
        } ?: return null
        TrackLog.d(TAG, "upgrade found: '${target.title}' at ${chosen.format.summary} from ${source.displayName}")
        return chosen
    }

    suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest = requestForDownload(),
        keep: suspend (SourceStream) -> SourceStream? = { it },
        budgetMs: Long? = null,
    ): SourceStream? = SourceDownloadResolver.forDownload(target, request, keep, budgetMs)

    internal fun beatsYouTubeAac(candidate: StreamFormat): Boolean =
        StreamQualityPolicy.beatsYouTubeAac(candidate)

    internal fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean =
        StreamQualityPolicy.worthSwapping(candidate, playing)

    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?, slackSec: Int = UPGRADE_DRIFT_SEC): Boolean {
        if (candidateSec == null || playingSec == null) return false
        return kotlin.math.abs(candidateSec - playingSec) <= slackSec
    }

    fun upgradeSlackSec(): Int = AppSettings.upgradeLengthSlackSeconds.value

    fun canSubstituteForYouTube(): Boolean =
        SourceRegistry.activeForPlayback().indexOfFirst { it.kind == SourceKind.YOUTUBE } > 0

    internal fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }

    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        driftSec: Int = UPGRADE_DRIFT_SEC,
        lengthLater: (suspend () -> Int?)? = null,
        checkFileLength: Boolean = false,
        refine: suspend (MusicSource, SourceStream) -> SourceStream? = { _, stream -> stream },
        timeoutMs: Long? = null,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val until = timeoutMs?.let { System.nanoTime() + it * 1_000_000 }
        val rank = sources.withIndex().associate { (index, source) -> source to index }
        val running: MutableList<Deferred<Pair<MusicSource, SourceStream?>>> = sources
            .map { source ->
                async {
                    source to matchAndStream(
                        source,
                        target,
                        request,
                        waitForAll,
                        strictLength,
                        requireSharedArtist,
                        driftSec,
                        lengthLater,
                        checkFileLength,
                    )?.let { refine(source, it) }
                }
            }
            .toMutableList()
        var best: Pair<MusicSource, SourceStream>? = null
        try {
            while (running.isNotEmpty()) {
                val awaiting = suspend {
                    select {
                        running.forEach { candidate -> candidate.onAwait { candidate } }
                    }
                }
                val first = if (until == null) {
                    awaiting()
                } else {
                    withTimeoutOrNull(remainingMs(until)) { awaiting() } ?: break
                }
                val ready = listOf(first) + running.filter { it !== first && it.isCompleted }
                running -= ready.toSet()
                for (done in ready) {
                    val (source, stream) = done.await()
                    if (stream == null) continue
                    if (!accept(source, stream)) continue
                    val held = best
                    val wins = held == null || isBetter(stream.format, held.second.format) ||
                        (!isBetter(held.second.format, stream.format) && rank.getValue(source) < rank.getValue(held.first))
                    if (wins) best = source to stream
                }
                if (best != null && !waitForAll) break
            }
        } finally {
            running.forEach { it.cancel() }
        }
        best
    }

    internal suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        driftSec: Int = UPGRADE_DRIFT_SEC,
        lengthLater: (suspend () -> Int?)? = null,
        checkFileLength: Boolean = false,
    ): SourceStream? {
        var matchOn = target
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) {
                source.search(
                    query,
                    limit = MATCH_CANDIDATES,
                    waitForAll = waitForAll,
                    request = request,
                )
            } ?: return null
            if (matchOn.durationSec == null && lengthLater != null) {
                lengthLater()?.let { matchOn = matchOn.copy(durationSec = it) }
            }
            val strict = strictLength && matchOn.durationSec != null
            var matches = TrackMatcher.ranked(candidates, matchOn)
            if (requireSharedArtist) {
                matches = matches.filter { TrackMatcher.sharesArtist(matchOn.artist, it.artist) }
            }
            if (source.kind == SourceKind.JIOSAAVN &&
                TrackMatcher.hasConflictingAlbums(matches, matchOn)
            ) {
                val canonical = TrackMatcher.uniquelyMostCreditedCloseMatch(matches, matchOn)
                if (canonical == null) {
                    TrackLog.w(
                        TAG,
                        "${source.displayName} returned conflicting albums for '${matchOn.title}'; refusing to guess",
                    )
                    continue
                }
                TrackLog.d(
                    TAG,
                    "${source.displayName} resolved conflicting albums for '${matchOn.title}' " +
                        "using the uniquely fullest credit: '${canonical.artist}'",
                )
                matches = listOf(canonical)
            }
            if (strict) {
                val (sameLength, otherLength) = matches.partition {
                    TrackMatcher.withinSeconds(it, matchOn, driftSec)
                }
                if (otherLength.isNotEmpty()) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName}: ${otherLength.size} match(es) for '${matchOn.title}' outside " +
                            "±${driftSec}s of ${matchOn.durationSec}s: " +
                            otherLength.joinToString { "'${it.title}' ${it.durationText ?: "?"}" },
                    )
                }
                matches = sameLength
            }
            if (matches.isEmpty()) continue
            return streamBest(
                source,
                matches,
                matchOn,
                request,
                sameLengthSec = if (strict) maxOf(SAME_RECORDING_SEC, driftSec) else SAME_RECORDING_SEC,
                compareRows = waitForAll,
                checkFileLength = checkFileLength,
            )
        }
        return null
    }

    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
        sameLengthSec: Int = SAME_RECORDING_SEC,
    ): List<Song> = StreamQualityPolicy.preferred(matches, target, wantsLossless, sameLengthSec)

    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        sameLengthSec: Int = SAME_RECORDING_SEC,
        compareRows: Boolean = false,
        checkFileLength: Boolean = false,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = preferred(matches, target, wantsLossless, sameLengthSec)
        var settleFor: SourceStream? = null
        var best: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            if (best != null) {
                if (best.format.isDolbyAtmos || match.sourceQuality != ModuleSource.LOSSLESS) break
            }
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source) { source.stream(trackId, request) } ?: continue
            val stream = opened.copy(
                durationSec = TrackMatcher.secondsOf(match.durationText),
                sourceConfigId = source.configId,
            )
            if (checkFileLength && cutShort(source, match, stream, target)) continue
            val served = stream.format
            if (!wantsLossless || served.isLossless == true || served.isDolbyAtmos || served.statesNothingLossy) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                        "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"} " +
                        "→ ${served.summary}",
                )
                if (!compareRows) return stream
                if (best == null || isBetter(stream.format, best.format)) best = stream
                continue
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${served.summary} for '${match.title}' " +
                    "by '${match.artist}' id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                    "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"}; looking further",
            )
            settleFor = betterOf(settleFor, stream.copy(belowRequest = true))
        }
        return best ?: settleFor
    }

    private suspend fun cutShort(
        source: MusicSource,
        match: Song,
        stream: SourceStream,
        target: TrackMatcher.Target,
    ): Boolean {
        val expectedSec = target.durationSec ?: TrackMatcher.secondsOf(match.durationText) ?: return false
        if (StreamContainer.isManifest(stream.url)) return false
        val bytes = StreamLengthProbe.bytesOf(stream) ?: return false
        if (!StreamLengthProbe.tooShort(bytes, stream.format, expectedSec, upgradeSlackSec())) return false
        TrackLog.w(
            TAG,
            "${source.displayName} served ${bytes / 1024}kB of ${stream.format.summary} for " +
                "'${match.title}', too little to hold ${expectedSec}s — a preview or a cut-off file; looking further",
        )
        return true
    }

    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean =
        StreamQualityPolicy.isBetter(candidate, current)

    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        StreamQualityPolicy.betterOf(current, candidate)

    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    internal fun remainingMs(until: Long): Long =
        ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    internal suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private const val MATCH_CANDIDATES = 15

    private const val STREAM_ATTEMPTS = 3

    private const val UPGRADE_DRIFT_SEC = 2

    private const val SAME_RECORDING_SEC = 3

}
