package com.bxane.nuvx.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
internal class StreamResolutionController(
    private val scope: CoroutineScope,
) {
    fun createResolver(): ResolvingDataSource.Resolver = ResolvingDataSource.Resolver { dataSpec ->
        val about = TrackLog.about(mediaIdIn(dataSpec.uri))
        if (dataSpec.uri.authority == "source") {
            mediaIdIn(dataSpec.uri)?.let { id -> runBlocking(about) { DirectSourceGate.awaitOpen(id) } }
            val directYouTube =
                dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1"
            val stream = runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    if (directYouTube) {
                        SourceResolver.youtubeFallback(SourceResolver.targetIn(dataSpec.uri))
                    } else {
                        SourceResolver.resolve(dataSpec.uri)
                    }
                }
            } ?: throw java.io.IOException(
                if (directYouTube) {
                    "No YouTube audio fallback matched ${dataSpec.uri.getQueryParameter("n")}"
                } else {
                    "No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}"
                },
            )
            val configId = dataSpec.uri.getQueryParameter("s")
            val sourceName = if (directYouTube) {
                "YouTube"
            } else {
                stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: configId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Source"
            }
            val trackParam = dataSpec.uri.getQueryParameter("t")
            val fullMediaId = mediaIdIn(dataSpec.uri)
            NerdStats.onSourceStream(fullMediaId ?: trackParam, stream.format, sourceName)
            if (fullMediaId != null) {
                NerdStats.recordSource(fullMediaId, sourceName)
            }
            NerdStats.recordSource(trackParam, sourceName)
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setHttpRequestHeaders(
                    if (directYouTube) StreamResolver.mediaHeadersFor(stream.url)
                    else stream.headers,
                )
                .build()
        }
        val videoId = dataSpec.uri.getQueryParameter("v")
            ?: return@Resolver dataSpec
        val analysisRead = AutomixAnalysisSource.requestsYouTubeOpus(
            dataSpec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
        )
        if (!analysisRead) runBlocking(about) { DirectSourceGate.awaitOpen(videoId) }
        if (dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") {
            QualityUpgrade.forget(videoId)
            StreamChoice.forget(videoId)
            NerdStats.clearDeclared(videoId)
            NerdStats.recordSource(videoId, "YouTube")
            val streamUrl = try {
                runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                }
            } catch (e: TimeoutCancellationException) {
                throw java.io.IOException("Direct YouTube resolution timed out for $videoId", e)
            }
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            TrackLog.d("nuvx", "serving original YouTube version for $videoId", about = videoId)
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        if (analysisRead) {
            NerdStats.recordSource(videoId, "YouTube")
            val streamUrl = try {
                runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                }
            } catch (e: TimeoutCancellationException) {
                throw java.io.IOException("Automix Opus resolution timed out for $videoId", e)
            }
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
            val proving = QualityUpgrade.isAuditioning(videoId)
            val sourceName = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Upgrade"
            if (!proving) {
                NerdStats.onSourceStream(videoId, upgraded.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
            }
            TrackLog.d(
                "nuvx",
                "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                    "from ${Uri.parse(upgraded.url).host} " +
                    "at ${dataSpec.position} (${upgraded.format.summary})",
                about = videoId,
            )
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(upgraded.url))
                .setHttpRequestHeaders(upgraded.headers)
                .build()
        }
        StreamChoice.of(videoId)?.let { serving ->
            val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
            if (serving.format != StreamFormat()) {
                NerdStats.onSourceStream(videoId, serving.format, sourceName)
            }
            NerdStats.recordSource(videoId, sourceName)
            if (!DirectSourceGate.isActive() &&
                StreamChoice.isSubstitute(videoId) &&
                serving.format.isLossless != true &&
                QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
            ) {
                val pending = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    playing = serving.format,
                    servedBy = serving.sourceConfigId,
                )
                if (!pending) NerdStats.onLosslessRaceEnd(videoId)
            }
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(serving.url))
                .setHttpRequestHeaders(serving.headers)
                .build()
        }
        if (!SourceResolver.canSubstituteForYouTube()) {
            val streamUrl = try {
                runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                }
            } catch (e: TimeoutCancellationException) {
                throw java.io.IOException("Stream resolution timed out for $videoId", e)
            }
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            NerdStats.recordSource(videoId, "YouTube")
            StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        if (AudioCache.heldByYouTube(dataSpec.key ?: AudioCache.cacheKeyOf(dataSpec.uri))) {
            val streamUrl = try {
                runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                }
            } catch (e: TimeoutCancellationException) {
                throw java.io.IOException("Stream resolution timed out for $videoId", e)
            }
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            NerdStats.recordSource(videoId, "YouTube")
            StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
            if (QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)) {
                val pending = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                )
                if (!pending) NerdStats.onLosslessRaceEnd(videoId)
            }
            return@Resolver dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        val won = runBlocking(about) {
            resolveWithModulePriority(
                videoId = videoId,
                target = SourceResolver.targetIn(dataSpec.uri),
            )
        }
        when (won) {
            is Resolved.Module -> {
                val sourceName = won.stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Module"
                NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
                StreamChoice.remember(videoId, won.stream, substituted = true)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(won.stream.url))
                    .setHttpRequestHeaders(won.stream.headers)
                    .build()
            }
            is Resolved.YouTube -> {
                NerdStats.recordSource(videoId, "YouTube")
                val headers = StreamResolver.mediaHeadersFor(won.url)
                StreamChoice.remember(videoId, SourceStream(won.url, headers = headers), substituted = false)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(won.url))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
        }
    }

    fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }

        val downloadedUri = Downloads.verifiedSavedUri(mediaId)
        if (downloadedUri != null) {
            return Uri.parse(downloadedUri)
        }

        return null
    }

    private sealed interface Resolved {
        data class Module(val stream: SourceStream) : Resolved
        data class YouTube(val url: String) : Resolved
    }

    private suspend fun resolveWithModulePriority(
        videoId: String,
        target: TrackMatcher.Target,
    ): Resolved {
        if (StreamChoice.substitutesRefused(videoId)) {
            return Resolved.YouTube(StreamResolver.resolve(videoId))
        }
        if (DirectSourceGate.isActive()) return resolveDirect(videoId, target)
        NerdStats.onLosslessRaceStart(videoId)
        val lookup = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) { SourceResolver.substituteForYouTube(target) }
        }
        val fallback = scope.async(Dispatchers.IO + TrackLog.about(videoId)) {
            runCatching { StreamResolver.resolve(videoId) }
        }

        val quick: SourceStream? = select {
            lookup.onAwait { it }
            fallback.onAwait { resolved -> if (resolved.isSuccess) null else lookup.await() }
        }

        if (quick != null && StreamContainer.isManifest(quick.url)) {
            val url = runCatching { fallback.await().getOrThrow() }.getOrNull()
            if (url != null) {
                TrackLog.d(
                    "nuvx",
                    "'${target.title}' was offered ${quick.format.summary} as a manifest; " +
                        "starting on YouTube and swapping to it under the music",
                    about = videoId,
                )
                val handed = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = target,
                    inFlight = CompletableDeferred(quick),
                    playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                )
                if (!handed) NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.YouTube(url)
            }
            TrackLog.w(
                "nuvx",
                "'${target.title}' has only a manifest and YouTube cannot serve it; " +
                    "letting it fail once to declare its type",
                about = videoId,
            )
            NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        if (quick != null) {
            fallback.cancel()
            if (!quick.belowRequest) {
                NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.Module(quick)
            }
            val settled = QualityUpgrade.settledForLess(
                mediaId = videoId,
                target = target,
                playing = quick.format,
                servedBy = quick.sourceConfigId,
            )
            if (!settled) NerdStats.onLosslessRaceEnd(videoId)
            return Resolved.Module(quick)
        }

        val url = fallback.await().getOrThrow()
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = lookup.takeIf { lookup.isActive },
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
        return Resolved.YouTube(url)
    }

    private suspend fun resolveDirect(videoId: String, target: TrackMatcher.Target): Resolved {
        val direct = runCatching { DirectSourceGate.lookup(videoId, target) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (direct != null) return Resolved.Module(direct)
        val running = DirectSourceGate.stillLooking(videoId)
        TrackLog.d(
            "nuvx",
            if (running != null) {
                "no addon answered for '${target.title}' within " +
                    "${DirectSourceGate.LOOKUP_TIMEOUT_MS / 1000}s; starting it on YouTube and still looking"
            } else {
                "no addon served '${target.title}' directly; playing it from YouTube"
            },
            about = videoId,
        )
        val url = try {
            StreamResolver.resolve(videoId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Resolved.Module(running?.await() ?: throw e)
        }
        DirectSourceGate.startedOnYouTube(videoId, target)
        return Resolved.YouTube(url)
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 120_000L

        const val SUBSTITUTE_TIMEOUT_MS = 20_000L

    }
}
