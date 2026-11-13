package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.DownloadQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs





























object SourceResolver {












    fun requestForNow(): StreamRequest {
        val ceiling = AppSettings.effectiveAudioQualityFlow.value
        return when {
            ceiling == com.bxane.nuvx.data.settings.AudioQuality.LOSSLESS -> StreamRequest.Lossless
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










    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
    ): SourceStream? {
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()





        val pinned = active.firstOrNull { it.configId == configId }





        if (request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue







                if (upgraded.format.isLossless != true) continue
                DebugLog.d("lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            val pinnedStream = attempt(pinned) { pinned.stream(trackId, request) }?.let { StreamVerification.verify(it) }
            if (pinnedStream != null) {
                if (!rejectUnsupportedNativeStream(pinned, pinnedStream, target.title)) {
                    return pinnedStream.copy(sourceConfigId = pinned.configId)
                }
            }
        }





        val (fallbackSource, stream) =
            bestAcross(active.filterNot { it.configId == configId }, target, request) ?: return null
        DebugLog.d("fallback: '${target.title}' served by ${fallbackSource.displayName}")
        return stream
    }





















    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {



        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val requested = requestForNow()
        val mayUseLossless = PlaybackCodecCapabilities.allowLosslessSources


        val routeRequest = if (requested is StreamRequest.Lossless && !mayUseLossless) StreamRequest.Best else requested
        val (source, stream) = bestAcross(
            rankedAbove(youtube.configId, active), target, routeRequest,
            allowLossless = mayUseLossless,
            accept = { _, candidate -> mayUseLossless || candidate.format.isLossless != true },
        )
            ?: return null




        DebugLog.d(
            "substituted: '${target.title}' served by ${source.displayName} over YouTube" +
                " at ${stream.format.summary}" + if (stream.belowRequest) " (below request)" else "",
        )
        return stream
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
            DebugLog.d("YouTube fallback matched '${target.title}' after its higher-quality source failed")
        }
    }





    suspend fun prefetchSubstitute(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val preferred = rankedAbove(youtube.configId, active)
        val request = requestForNow()
        val mayUseLossless = PlaybackCodecCapabilities.allowLosslessSources
        val routeRequest = if (request is StreamRequest.Lossless && !mayUseLossless) StreamRequest.Best else request
        val (source, stream) = bestAcross(
            preferred, target, routeRequest,
            waitForAll = true,
            selectionWindowMs = if (routeRequest is StreamRequest.Lossless) 12_000 else 2_500,
            allowLossless = mayUseLossless,
            accept = { _, candidate -> mayUseLossless || candidate.format.isLossless != true },
        ) ?: return null
        DebugLog.d("warmed: '${target.title}' from ${source.displayName} at ${stream.format.summary}")
        return stream
    }































    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null















        val (source, chosen) = bestAcross(
            rankedAbove(youtube.configId, active).filterNot { it.configId == servedBy },
            target,
            if (requestForNow() is StreamRequest.Lossless && !PlaybackCodecCapabilities.allowLosslessSources) {
                StreamRequest.Best
            } else requestForNow(),
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            allowLossless = PlaybackCodecCapabilities.allowLosslessSources,
        ) { candidate, stream ->
            (PlaybackCodecCapabilities.allowLosslessSources || stream.format.isLossless != true) &&
                worthSwapping(stream.format, playing).also { worth ->




                if (!worth) {
                    DebugLog.d(
                        "${candidate.displayName}'s ${stream.format.summary} isn't worth swapping " +
                            "'${target.title}' off ${playing?.summary ?: "an unmeasured stream"}",
                    )
                }
            }
        } ?: return null
        DebugLog.d("upgrade found: '${target.title}' at ${chosen.format.summary} from ${source.displayName}")
        return chosen
    }















































    suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest = requestForDownload(),
    ): SourceStream? = coroutineScope {
        if (target.title.isBlank()) return@coroutineScope null
        val wantsLossless = when (request) {
            is StreamRequest.Lossless -> true
            is StreamRequest.Best -> false





            is StreamRequest.Capped -> return@coroutineScope null
        }






        val active = SourceRegistry.active().filter { (it as? AddonSource)?.allowsDownloads() != false }



        val youtubeId = active.firstOrNull { it.kind == SourceKind.YOUTUBE }?.configId
        val ranked = rankedAbove(youtubeId.orEmpty(), active)
        val strictLength = target.durationSec != null






        val bitExact = if (wantsLossless) ranked.filter { it.kind.canServeLossless } else emptyList()
        val elsewhere = ranked - bitExact.toSet()

        val elsewhereBest = async {
            bestAcross(
                elsewhere,
                target,



                StreamRequest.Best,
                waitForAll = true,
                strictLength = strictLength,
            ) { source, stream ->






                val ok = wantsLossless || stream.format.isLossless != true
                if (!ok) {
                    DebugLog.d(
                        "${source.displayName} offered ${stream.format.summary}; " +
                            "more than this download asked for",
                    )
                }
                ok
            }
        }

        var best: Pair<MusicSource, SourceStream>? = null
        for (source in bitExact) {
            val stream = matchAndStream(
                source = source,
                target = target,


                request = request,
                waitForAll = true,
                strictLength = strictLength,
            ) ?: continue




            if (stream.format.isLossless == true || stream.format.isDolbyAtmos) {
                DebugLog.d("download: '${target.title}' from ${source.displayName} at ${stream.format.summary}")



                elsewhereBest.cancel()
                return@coroutineScope stream
            }
            DebugLog.d("${source.displayName} offered ${stream.format.summary} to download; not lossless or Dolby")
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }



        elsewhereBest.await()?.let { (source, stream) ->
            if (isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        val (winner, chosen) = best ?: return@coroutineScope null
        if (!beatsYouTubeAac(chosen.format)) {
            DebugLog.d(
                "best offered for '${target.title}' is ${winner.displayName}'s " +
                    "${chosen.format.summary}; taking YouTube's AAC over that",
            )
            return@coroutineScope null
        }
        DebugLog.d(
            "download: '${target.title}' from ${winner.displayName} at ${chosen.format.summary}, " +
                "over YouTube's AAC",
        )
        chosen
    }









    internal fun beatsYouTubeAac(candidate: StreamFormat): Boolean =
        (candidate.kbps ?: 0) > YOUTUBE_BEST_AAC_KBPS












    internal fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean {



        if (playing?.isDolbyAtmos == true && !candidate.isDolbyAtmos) return false
        if (candidate.isDolbyAtmos) return playing?.isDolbyAtmos != true
        if (candidate.isLossless == true) return playing?.isLossless != true || isBetter(candidate, playing)
        val baseline = playing ?: return false
        if (baseline.isLossless == true || candidate.codecFamily == null || candidate.codecFamily != baseline.codecFamily) return false
        val gain = (candidate.kbps ?: return false) - (baseline.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }









    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?): Boolean {
        if (candidateSec == null || playingSec == null) return false
        return abs(candidateSec - playingSec) <= upgradeLengthSlackSeconds()
    }

    private fun upgradeLengthSlackSeconds(): Int =
        runCatching { AppSettings.upgradeLengthSlackSeconds.value }.getOrDefault(3).coerceIn(0, 10)









    fun canSubstituteForYouTube(): Boolean =
        SourceRegistry.activeForPlayback().indexOfFirst { it.kind == SourceKind.YOUTUBE } > 0









    private fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> {
        val index = active.indexOfFirst { it.configId == configId }
        return active.take(if (index < 0) active.size else index)
    }








    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        allowLossless: Boolean = true,
        selectionWindowMs: Long = if (!waitForAll && request is StreamRequest.Lossless) 2_500 else 0,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val running: MutableList<Deferred<Pair<MusicSource, SourceStream?>>> = sources
            .map { source ->
                async {
                    source to matchAndStream(
                        source = source,
                        target = target,
                        request = request,
                        waitForAll = waitForAll,
                        strictLength = strictLength,
                        requireSharedArtist = requireSharedArtist,
                        allowLossless = allowLossless,
                    )
                }
            }
            .toMutableList()
        var best: Pair<MusicSource, SourceStream>? = null
        suspend fun collect() {
            while (running.isNotEmpty()) {
                val first = select {
                    running.forEach { candidate -> candidate.onAwait { candidate } }
                }




                val ready = listOf(first) + running.filter { it !== first && it.isCompleted }
                running -= ready.toSet()
                for (done in ready) {
                    val (source, stream) = done.await()
                    if (stream == null) continue
                    if (!accept(source, stream)) continue
                    val previous = best
                    if (isBetter(stream.format, previous?.second?.format) ||
                        (previous != null && !isBetter(previous.second.format, stream.format) &&
                            sources.indexOf(source) < sources.indexOf(previous.first))) {
                        best = source to stream
                    }
                }




                if (best != null && !waitForAll && selectionWindowMs <= 0) break
            }
        }
        try {
            if (selectionWindowMs > 0) {


                if (withTimeoutOrNull(selectionWindowMs) { collect(); true } == null) {
                    SourceResolutionReport.record("timeout")
                }
            } else {
                collect()
            }
        } finally {
            running.forEach { it.cancel() }
        }
        best
    }




















    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        allowLossless: Boolean = true,
    ): SourceStream? {
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) {
                source.search(
                    query = query,
                    limit = MATCH_CANDIDATES,
                    waitForAll = waitForAll,



                    request = request,
                )
            } ?: return null
            var matches = TrackMatcher.ranked(candidates, target)
            if (requireSharedArtist) {
                matches = matches.filter { TrackMatcher.sharesArtist(target.artist, it.artist) }
            }





            if (source.kind == SourceKind.JIOSAAVN &&
                TrackMatcher.hasConflictingAlbums(matches, target)
            ) {
                val canonical = TrackMatcher.uniquelyMostCreditedCloseMatch(matches, target)
                if (canonical == null) {
                    DebugLog.w(
                        "${source.displayName} returned conflicting albums for '${target.title}'; " +
                            "refusing to guess",
                    )
                    continue
                }
                DebugLog.d(
                    "${source.displayName} resolved conflicting albums for '${target.title}' " +
                        "using the uniquely fullest credit: '${canonical.artist}'",
                )
                matches = listOf(canonical)
            }






            if (strictLength) {
                matches = matches.filter { TrackMatcher.withinSeconds(it, target, upgradeLengthSlackSeconds()) }
            }
            if (matches.isEmpty()) continue
            return streamBest(source, matches, target, request, allowLossless)
        }
        return null
    }























    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        if (!wantsLossless) return eligible


        return eligible.sortedByDescending { it.sourceQuality == LOSSLESS_QUALITY }
    }

    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        allowLossless: Boolean,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = preferred(matches, target, wantsLossless)
        var settleFor: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source) { source.stream(trackId, request) } ?: continue



            val stream = StreamVerification.verify(opened.copy(
                durationSec = TrackMatcher.secondsOf(match.durationText),
                sourceConfigId = source.configId,
            )) ?: continue
            val served = stream.format
            if (rejectUnsupportedNativeStream(source, stream, match.title)) continue
            if (!allowLossless && served.isLossless == true) {
                DebugLog.d("${source.displayName} offered lossless for '${match.title}' on a route that cannot carry it; trying another match")
                continue
            }
            if (!wantsLossless || served.isLossless == true || served.isDolbyAtmos || served.statesNothingLossy) {
                DebugLog.d(
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "at ${served.summary}",
                )
                return stream
            }
            SourceResolutionReport.record("provider_lossy")
            DebugLog.d(
                "${source.displayName} offered ${served.summary} for '${match.title}'; looking further",
            )





            settleFor = betterOf(settleFor, stream.copy(belowRequest = true))
        }
        if (settleFor == null) SourceResolutionReport.record("no_match")
        return settleFor
    }


    private fun rejectUnsupportedNativeStream(
        source: MusicSource,
        stream: SourceStream,
        title: String,
    ): Boolean {
        if (!stream.format.isKnownUnsupportedByNativeDecoder ||
            PlaybackCodecCapabilities.canRenderDolby(stream.format.codec, stream.headers)) return false
        DebugLog.d(
            "${source.displayName} offered ${stream.format.summary} for '$title', " +
                "which the native decoder cannot play; trying another source",
        )
        return true
    }














    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean {
        if (current == null) return true







        if (candidate.isDolbyAtmos != current.isDolbyAtmos) return candidate.isDolbyAtmos
        if (candidate.isLossless != current.isLossless) return candidate.isLossless == true
        if (candidate.isLossless == true) {

            val depth = candidate.bitDepth ?: 0
            val rate = candidate.sampleRateHz ?: 0
            val oldDepth = current.bitDepth ?: 0
            val oldRate = current.sampleRateHz ?: 0
            return depth >= oldDepth && rate >= oldRate && (depth > oldDepth || rate > oldRate)
        }


        if (candidate.codecFamily == null || candidate.codecFamily != current.codecFamily) return false
        return (candidate.kbps ?: 0) > (current.kbps ?: 0)
    }


    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current









    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null









    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DebugLog.w("${source.displayName} failed: ${e::class.simpleName}: ${e.message}")
        null
    }












    private const val MATCH_CANDIDATES = 15









    private const val STREAM_ATTEMPTS = 3
















    private const val SAME_RECORDING_SEC = 3











    private const val YOUTUBE_BEST_AAC_KBPS = 256








    private const val UPGRADE_MIN_GAIN_KBPS = 96


    private const val LOSSLESS_QUALITY = "LOSSLESS"
}
