package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.TrackLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal object SourceDownloadResolver {
    private const val TAG = "nuvx"

    suspend fun forDownloadOfRow(
        configId: String,
        trackId: String,
        sourceQuality: String?,
        request: StreamRequest = SourceResolver.requestForDownload(),
    ): SourceStream? {
        val source = SourceRegistry.activeForDownload().firstOrNull { it.configId == configId } ?: return null
        return SourceResolver.attempt(source) {
            if (source is AddonSource) {
                source.stream(trackId, request, stereoOnly = SourceResolver.mixOf(sourceQuality) == SourceResolver.MIX_STEREO)
            } else {
                source.stream(trackId, request)
            }
        }?.copy(sourceConfigId = source.configId)
    }

    suspend fun forDownload(
        target: TrackMatcher.Target,
        request: StreamRequest = SourceResolver.requestForDownload(),
        keep: suspend (SourceStream) -> SourceStream? = { it },
        budgetMs: Long? = null,
    ): SourceStream? = coroutineScope {
        val until = budgetMs?.let { System.nanoTime() + it * 1_000_000 }
        if (target.title.isBlank()) return@coroutineScope null
        val wantsLossless = when (request) {
            is StreamRequest.Lossless -> true
            is StreamRequest.Best -> false
            is StreamRequest.Capped -> return@coroutineScope null
        }
        val active = SourceRegistry.activeForDownload()
        val youtubeId = active.firstOrNull { it.kind == SourceKind.YOUTUBE }?.configId
        val ranked = SourceResolver.rankedAbove(youtubeId.orEmpty(), active)
        val strictLength = target.durationSec != null

        val bitExact = if (wantsLossless) ranked.filter { it.kind.canServeLossless } else emptyList()
        val elsewhere = ranked - bitExact.toSet()

        var losslessOverBudget: Pair<MusicSource, SourceStream>? = null
        val elsewhereBest = async {
            SourceResolver.bestAcross(
                elsewhere,
                target,
                StreamRequest.Best,
                waitForAll = true,
                strictLength = strictLength,
                refine = { _, stream -> keep(stream) },
                timeoutMs = budgetMs,
            ) { source, stream ->
                val ok = wantsLossless || stream.format.isLossless != true
                if (!ok) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName} offered ${stream.format.summary}; " +
                            "more than this download asked for, kept in reserve",
                    )
                    if (losslessOverBudget == null) losslessOverBudget = source to stream
                }
                ok
            }
        }

        val bitExactRuns = bitExact.map { source ->
            source to async {
                SourceResolver.matchAndStream(
                    source,
                    target,
                    request,
                    waitForAll = true,
                    strictLength = strictLength,
                )?.let { keep(it) }
            }
        }

        var best: Pair<MusicSource, SourceStream>? = null
        try {
            for ((source, run) in bitExactRuns) {
                val stream = when {
                    run.isCompleted || until == null -> run.await()
                    else -> withTimeoutOrNull(SourceResolver.remainingMs(until)) { run.await() }.also {
                        if (it == null && !run.isCompleted) {
                            TrackLog.w(TAG, "${source.displayName} ran out of download lookup time")
                        }
                    }
                } ?: continue
                if (stream.format.isLossless == true || stream.format.isDolbyAtmos) {
                    TrackLog.d(
                        TAG,
                        "download: '${target.title}' from ${source.displayName} at ${stream.format.summary}",
                    )
                    elsewhereBest.cancel()
                    return@coroutineScope stream
                }
                TrackLog.d(
                    TAG,
                    "${source.displayName} offered ${stream.format.summary} to download; not lossless or Dolby",
                )
                if (SourceResolver.isBetter(stream.format, best?.second?.format)) best = source to stream
            }
        } finally {
            bitExactRuns.forEach { (_, run) -> run.cancel() }
        }

        elsewhereBest.await()?.let { (source, stream) ->
            if (SourceResolver.isBetter(stream.format, best?.second?.format)) best = source to stream
        }
        val lossy = best?.takeIf { SourceResolver.beatsYouTubeAac(it.second.format) }
        if (lossy == null) {
            losslessOverBudget?.let { (source, stream) ->
                TrackLog.d(
                    TAG,
                    "download: nothing lossy for '${target.title}' beats YouTube's AAC; " +
                        "keeping ${source.displayName}'s ${stream.format.summary} instead",
                )
                return@coroutineScope stream
            }
        }
        val (winner, chosen) = lossy ?: run {
            best?.let { (source, stream) ->
                TrackLog.d(
                    TAG,
                    "best offered for '${target.title}' is ${source.displayName}'s " +
                        "${stream.format.summary}; taking YouTube's AAC over that",
                )
            }
            return@coroutineScope null
        }
        TrackLog.d(
            TAG,
            "download: '${target.title}' from ${winner.displayName} at ${chosen.format.summary}, " +
                "over YouTube's AAC",
        )
        chosen
    }
}
