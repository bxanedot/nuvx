package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.addon.AddonAuth
import com.bxane.nuvx.data.sources.addon.AddonClient
import com.bxane.nuvx.data.sources.addon.AddonException
import com.bxane.nuvx.data.sources.addon.AddonManifest
import com.bxane.nuvx.data.sources.addon.AddonNotFound
import com.bxane.nuvx.data.sources.addon.AddonStream
import com.bxane.nuvx.data.sources.addon.AddonTrack
import com.bxane.nuvx.data.sources.addon.AddonUnavailable
import com.bxane.nuvx.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

class AddonSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    private val client = AddonClient(config.baseUrl, config.pickedQuality)

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > MAX_ROWS
        },
    )

    private val live: SourceConfig get() = SourceRegistry.config(config.id) ?: config

    private fun shutOut(what: String): Boolean {
        if (!live.awaitsLosslessOutput) return false
        TrackLog.d(TAG, "${config.displayName}: no lossless output connected; not asking it for $what")
        return true
    }

    private suspend fun admitted(what: String): Boolean {
        if (shutOut(what)) return false
        val declared = refreshPolicy()
        if (shutOut(what)) return false
        if ((declared ?: live.nuvxAuth) != true) return true
        AddonAuth.standingRefusal()?.let { reason ->
            if (reason != loggedRefusal) {
                loggedRefusal = reason
                TrackLog.d(TAG, "${config.displayName}: skipped until verification is retried — $reason")
            }
            return false
        }
        loggedRefusal = null
        return true
    }

    @Volatile
    private var loggedRefusal: String? = null

    suspend fun refreshPolicy(): Boolean? =
        client.manifest().fold(
            onSuccess = { manifest ->
                recordPolicy(manifest)
                manifest.requiresNuvxAuth
            },
            onFailure = { failure ->
                if (failure is AddonException) {
                    SourceRegistry.markAddonOutdated(config.id)
                    false
                } else {
                    null
                }
            },
        )

    private fun recordPolicy(manifest: AddonManifest) =
        SourceRegistry.updatePolicy(
            config.id,
            manifest.downloadsAllowed,
            manifest.requiresLosslessOutput,
            manifest.qualityOptions,
            manifest.requiresNuvxAuth,
        )

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) {
            return@withContext SourceHealth.Rejected("An addon URL is required")
        }
        if (shutOut("a health check")) {
            return@withContext SourceHealth.Rejected(NEEDS_LOSSLESS_OUTPUT)
        }
        client.manifest().fold(
            onSuccess = { manifest ->
                recordPolicy(manifest)
                if (manifest.requiresNuvxAuth) client.checkAuth().exceptionOrNull()?.let { failure ->
                    val reason = failure.message ?: "nuvx couldn't verify this device"
                    return@fold if (failure is AddonUnavailable) {
                        SourceHealth.Unreachable(reason)
                    } else {
                        SourceHealth.Rejected(reason)
                    }
                }
                SourceHealth.Ok(
                    listOfNotNull(
                        manifest.displayName.takeIf { it.isNotBlank() },
                        manifest.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" ").ifBlank { null },
                )
            },
            onFailure = { failure ->
                if (failure is AddonException) SourceRegistry.markAddonOutdated(config.id)
                if (client.probeSearch().isSuccess) {
                    TrackLog.d(TAG, "${config.displayName}: no manifest, but search answers")
                    return@fold SourceHealth.Ok("No manifest · search works")
                }
                val reason = failure.message ?: "Could not reach the addon"
                if (failure is AddonException) {
                    SourceHealth.Rejected(reason)
                } else {
                    TrackLog.w(TAG, "addon unreachable for ${config.displayName}: $reason")
                    SourceHealth.Unreachable(reason)
                }
            },
        )
    }

    suspend fun manifestName(): String? =
        if (shutOut("its name")) null else client.manifest().getOrNull()?.displayName?.ifBlank { null }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            if (!admitted("a search")) return@withContext emptyList()
            val tier = request?.tier ?: AddonClient.TIER_LOSSLESS
            val tracks = client.search(query, tier).getOrElse { failure ->
                TrackLog.w(TAG, "${config.displayName}: search failed — ${failure.message}")
                return@withContext emptyList()
            }
            tracks.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .take(limit)
                .map { track ->
                    rows[track.id] = track
                    Song(
                        videoId = SourceRegistry.trackKey(config.id, track.id),
                        title = track.title,
                        artist = track.artist,
                        albumName = track.album.ifBlank { null },
                        thumbnailUrl = track.artwork,
                        durationText = track.durationSec?.let {
                            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                        },
                        sourceQuality = if (track.isDolbyAtmos) {
                            ModuleSource.DOLBY
                        } else {
                            ModuleSource.qualityTier("${track.audioQuality} ${track.format}")
                        },
                        sourceHiRes = !track.isDolbyAtmos && track.isHiRes,
                    )
                }
                .toList()
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        stream(trackId, request, stereoOnly = false)

    suspend fun stream(trackId: String, request: StreamRequest, stereoOnly: Boolean): SourceStream? =
        withContext(Dispatchers.IO) {
            if (!admitted("a stream")) return@withContext null
            val tier = request.tier
            val result = client.stream(trackId, tier, stereoOnly)
            val answer = result.getOrNull()

            if (answer == null) {
                val failure = result.exceptionOrNull()
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "${config.displayName}: stream failed for $trackId — ${failure?.message}")
                }
                return@withContext fromRow(trackId, tier)
            }

            val url = answer.url.ifBlank { null } ?: run {
                TrackLog.w(
                    TAG,
                    "${config.displayName}: no stream for $trackId" +
                        (answer.error?.ifBlank { null }?.let { " — $it" } ?: ""),
                )
                return@withContext fromRow(trackId, tier)
            }

            openable(url, answer, trackId, tier).also { stream ->
                if (stereoOnly && stream?.format?.isDolbyAtmos == true) {
                    TrackLog.w(
                        TAG,
                        "${config.displayName}: asked for the stereo mix of $trackId with no Atmos hint " +
                            "and was sent Atmos anyway",
                    )
                }
            }
        }

    private fun fromRow(trackId: String, tier: String): SourceStream? {
        val row = rows[trackId] ?: return null
        val url = row.streamURL?.ifBlank { null } ?: return null
        TrackLog.d(TAG, "${config.displayName}: using the search row's own URL for $trackId")
        return openable(url, AddonStream(url = url, format = row.format), trackId, tier)
    }

    private fun openable(url: String, answer: AddonStream, trackId: String, tier: String): SourceStream? {
        if (ModuleSource.malformed(url)) {
            TrackLog.w(
                TAG,
                "${config.displayName}: malformed URL for $trackId; skipping it — ${url.take(120)}",
            )
            return null
        }
        if (answer.isEncrypted) {
            TrackLog.w(
                TAG,
                "${config.displayName}: $trackId came back encrypted, which nuvx never asked for — passing",
            )
            return null
        }

        val format = formatOf(answer, url, tier)
        if (ModuleSource.unplayable(format, atmosAllowed = DeviceCodecs.atmosWanted)) {
            val why = DeviceCodecs.atmosRefusal()
            TrackLog.w(
                TAG,
                "${config.displayName}: answered a $tier request with ${format.summary}, $why — passing",
            )
            return null
        }

        answer.transport?.let { StreamContainer.declare(url, it) }

        return SourceStream(
            url = url,
            format = format,
            durationSec = rows[trackId]?.durationSec,
        )
    }

    private fun formatOf(answer: AddonStream, url: String, tier: String): StreamFormat {
        val codec = answer.statedCodec?.takeIf { it in AUDIO_CODECS }
            ?: answer.statedContainer?.takeIf { it in SELF_DESCRIBING_CONTAINERS }
            ?: answer.mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim()
                ?.lowercase(Locale.ROOT)?.takeIf { it in AUDIO_CODECS }
            ?: if (answer.isDolbyAtmos) {
                "eac3-joc"
            } else if (ModuleSource.qualityTier(answer.qualityText) == ModuleSource.LOSSLESS) {
                "flac"
            } else {
                url.substringBefore('?').substringAfterLast('.')
                    .lowercase(Locale.ROOT).takeIf { it in AUDIO_CODECS }
            }

        val tierKbps = when (tier) {
            AddonClient.TIER_HIGH -> 320
            AddonClient.TIER_LOW -> 128
            else -> null
        }
        return StreamFormat(
            codec = codec,
            kbps = answer.kbps ?: tierKbps,
            sampleRateHz = answer.sampleRateHz,
            bitDepth = answer.bits,
            kbpsInferred = answer.kbps == null && tierKbps != null,
        )
    }

    private val StreamRequest.tier: String
        get() = when (this) {
            is StreamRequest.Lossless -> AddonClient.TIER_LOSSLESS
            is StreamRequest.Best -> AddonClient.TIER_HIGH
            is StreamRequest.Capped ->
                if (maxKbps <= ModuleSource.LOW_CEILING_KBPS) AddonClient.TIER_LOW else AddonClient.TIER_HIGH
        }

    fun release() {
        rows.clear()
        client.clear()
    }

    fun clearCompletedTrackCalls() {
        client.clearCompletedTrackCalls()
    }

    private companion object {
        const val TAG = "nuvx"

        const val NEEDS_LOSSLESS_OUTPUT =
            "This addon only works with a lossless output — use a lossless supported device"

        const val MAX_ROWS = 256

        val SELF_DESCRIBING_CONTAINERS = setOf("flac", "wav", "mp3", "aiff")

        val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4",
            "ogg", "opus", "vorbis", "webm", "eac3-joc", "ec3-joc",
        )
    }
}
