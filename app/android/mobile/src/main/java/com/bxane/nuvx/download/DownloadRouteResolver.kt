package com.bxane.nuvx.download

import android.content.Context
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.innertube.StreamResolver
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.SourceStream
import com.bxane.nuvx.data.sources.StreamFormat
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.playback.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal object DownloadRouteResolver {
    private const val TAG = "nuvx"
    private const val SOURCE_LOOKUP_MS = 85_000L
    private const val LOOKUP_BACKSTOP_MS = 15_000L

    internal suspend fun routeFor(context: Context, track: Song, quality: DownloadQuality): Downloads.Route {
        val row = SourceRegistry.parseTrackKey(track.videoId)
        row?.let { (configId, trackId) ->
            fromRow(track, configId, trackId, quality)?.let { kept ->
                sourceRoute(context, track, kept)?.let { return it }
            }
        }
        fromSources(track, quality)?.let { kept ->
            sourceRoute(context, track, kept)?.let { return it }
        }
        if (row != null) {
            val name = SourceRegistry.config(row.first)?.displayName ?: "its source"
            error("Couldn't download this song from $name — check that it's still connected")
        }
        val requireM4a = AppSettings.exportDownloads.value
        val stream = StreamResolver.resolveForDownload(track.videoId, quality.maxKbps, requireM4a)
        return Downloads.Route(
            extension = stream.downloadExtension,
            mimeType = stream.downloadMimeType,
            describe = "${stream.kbps}kbps ${stream.mimeType}",
            downloadFormat = youtubeDownloadBadge(stream.downloadExtension, stream.kbps),
            write = { sink, onProgress ->
                Downloader.fetch(track.videoId, stream, quality.maxKbps, requireM4a, sink, onProgress)
            },
        )
    }

    private class Kept(val stream: SourceStream, val probe: Downloader.Probe)

    private fun sourceRoute(context: Context, track: Song, kept: Kept): Downloads.Route? {
        val stream = kept.stream
        return when (val probe = kept.probe) {
            is Downloader.Probe.File -> Downloads.Route(
                extension = probe.container.extension,
                mimeType = probe.container.mimeType,
                describe = stream.format.summary,
                downloadFormat = stream.format.downloadBadge(),
                write = { sink, onProgress ->
                    Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
                },
            )
            is Downloader.Probe.Manifest -> {
                val dash = probe.dash
                val assembled = DownloadStore.storable(stream.format.codec)
                    ?.takeIf { it.extension in ManifestFile.EXTENSIONS }
                when {
                    assembled != null -> Downloads.Route(
                        extension = assembled.extension,
                        mimeType = assembled.mimeType,
                        describe = "${stream.format.summary} (assembled from ${if (dash) "DASH" else "HLS"})",
                        downloadFormat = stream.format.downloadBadge(),
                        write = { sink, onProgress ->
                            ManifestFile.write(
                                context.cacheDir, stream.url, stream.headers, dash,
                                assembled.extension, sink, onProgress,
                            )
                        },
                    )
                    AppSettings.exportDownloads.value -> {
                        Log.d(TAG, "can't export a ${stream.format.summary} manifest for ${track.videoId}")
                        null
                    }
                    else -> Downloads.Route(
                        extension = "m3u8",
                        mimeType = "application/vnd.apple.mpegurl",
                        describe = stream.format.summary,
                        downloadFormat = stream.format.downloadBadge(),
                        offlineHls = Downloads.Manifest(stream.url, stream.headers, dash = dash),
                        write = { sink, onProgress ->
                            Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
                        },
                    )
                }
            }
        }
    }

    internal fun cachedRoute(copy: AudioCache.CompleteCopy): Downloads.Route? {
        if (copy.extension == "webm" && AppSettings.exportDownloads.value) return null
        return Downloads.Route(
            extension = copy.extension,
            mimeType = copy.mimeType,
            describe = "song cache, ${copy.length} bytes",
            downloadFormat = if (copy.extension == "flac") "FLAC" else null,
            write = { sink, onProgress -> AudioCache.copyOut(copy, sink, onProgress) },
        )
    }

    private suspend fun fromSources(track: Song, quality: DownloadQuality): Kept? {
        val target = TrackMatcher.targetOf(track)
        val probes = java.util.concurrent.ConcurrentHashMap<String, Downloader.Probe>()
        var timedOut = true
        val stream = withTimeoutOrNull(SOURCE_LOOKUP_MS + LOOKUP_BACKSTOP_MS) {
            try {
                SourceResolver.forDownload(
                    target,
                    SourceResolver.requestForDownload(quality),
                    keep = { candidate ->
                        keepable(candidate, target.durationSec)
                            ?.also { probes[it.stream.url] = it.probe }
                            ?.stream
                    },
                    budgetMs = SOURCE_LOOKUP_MS,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source lookup failed for ${track.videoId}: ${e.message}")
                null
            }.also { timedOut = false }
        }
        if (timedOut) {
            Log.w(TAG, "source lookup for ${track.videoId} overran its own budget; downloading it from YouTube")
        }
        stream ?: return null
        return probes[stream.url]?.let { Kept(stream, it) }
    }

    private suspend fun fromRow(track: Song, configId: String, trackId: String, quality: DownloadQuality): Kept? {
        val stream = try {
            SourceResolver.forDownloadOfRow(
                configId,
                trackId,
                track.sourceQuality,
                SourceResolver.requestForDownload(quality),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "row lookup failed for ${track.videoId}: ${e.message}")
            null
        }
        if (stream == null) {
            Log.d(TAG, "${track.videoId}: its own source has nothing to download; matching it elsewhere")
            return null
        }
        return keepable(stream, TrackMatcher.targetOf(track).durationSec)
    }

    private suspend fun keepable(stream: SourceStream, durationSec: Int?): Kept? {
        val probe = Downloader.probe(stream.url, stream.headers, stream.format.codec)
        if (probe == null) {
            Log.d(TAG, "${stream.format.summary}: not a file a download can keep; passing")
            return null
        }
        val export = AppSettings.exportDownloads.value
        return when (probe) {
            is Downloader.Probe.Manifest -> {
                val assembles = DownloadStore.storable(stream.format.codec)?.extension in ManifestFile.EXTENSIONS
                if (export && !assembles) {
                    Log.d(TAG, "${stream.format.summary}: a manifest an export can't file; passing")
                    return null
                }
                Kept(stream, probe)
            }
            is Downloader.Probe.File -> {
                val container = probe.container
                if (export && container == Downloader.Container.WEBM) {
                    Log.d(TAG, "${stream.format.summary}: WebM can't be exported; passing")
                    return null
                }
                val described = stream.format.copy(
                    codec = container.codec ?: stream.format.codec ?: container.extension,
                )
                val measured = estimatedKbps(probe.totalBytes, stream.durationSec ?: durationSec)
                    ?.takeIf { described.kbps == null && described.isLossless != true }
                val format = if (measured != null) {
                    described.copy(kbps = measured, kbpsInferred = true)
                } else {
                    described
                }
                Kept(stream.copy(format = format), probe)
            }
        }
    }

    internal fun estimatedKbps(totalBytes: Long?, durationSec: Int?): Int? {
        if (totalBytes == null || durationSec == null || durationSec <= 0) return null
        return (totalBytes * 8 / 1000 / durationSec).toInt().takeIf { it > 0 }
    }
}

private fun StreamFormat.downloadBadge(): String? = when {
    isDolbyAtmos -> "DOLBY"
    codec.equals("flac", ignoreCase = true) || codec.equals("x-flac", ignoreCase = true) -> "FLAC"
    else -> null
}
