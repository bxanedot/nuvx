package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.sources.addon.AddonClient
import com.bxane.nuvx.data.sources.addon.AddonNotFound
import com.bxane.nuvx.data.sources.addon.AddonStream
import com.bxane.nuvx.data.sources.addon.AddonTrack
import com.bxane.nuvx.data.sources.addon.AddonUnavailable











class AddonSource(override val config: SourceConfig) : MusicSource, ConfigBacked {

    private val client = AddonClient(SourceRegistry.baseUrlOf(config), config.refreshUrl.takeIf { config.refreshEnabled })


    private suspend fun manifestFlags() = client.manifest().getOrNull()


    suspend fun allowsDownloads(): Boolean = manifestFlags()?.downloadsAllowed ?: true


    private suspend fun outputPermitted(): Boolean =
        PlaybackCodecCapabilities.losslessOutput || manifestFlags()?.requiresLosslessOutput != true

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName









    override suspend fun health(): SourceHealth {
        val manifest = client.manifest()
        manifest.exceptionOrNull()?.let { failure ->
            return when (failure) {

                is AddonUnavailable -> SourceHealth.Unreachable(failure.message ?: "Unavailable")
                else -> SourceHealth.Rejected(failure.message ?: "Not usable")
            }
        }



        return client.probeSearch().fold(
            onSuccess = { count ->
                val name = manifest.getOrNull()?.displayName ?: displayName
                SourceHealth.Ok(if (count > 0) "$name · $count rows" else "$name · reachable")
            },
            onFailure = { failure ->
                when (failure) {
                    is AddonUnavailable -> SourceHealth.Unreachable(failure.message ?: "Unavailable")
                    else -> SourceHealth.Rejected(failure.message ?: "Search endpoint did not answer")
                }
            },
        )
    }










    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {


        if (request != null && !outputPermitted()) return emptyList()
        val tier = request?.tier ?: AddonClient.TIER_HIGH
        val result = client.search(query, tier)
        result.exceptionOrNull()?.let { failure ->


            if (failure !is AddonNotFound) {
                DebugLog.w("${config.displayName} search failed: ${failure.message}")
            }
            return emptyList()
        }
        return result.getOrDefault(emptyList())
            .asRows()
            .sortedByDescending { it.rank(request) }
            .take(limit)
            .map { it.toSong(config.id) }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (!outputPermitted()) return null
        val result = client.stream(trackId, request.tier)
        result.exceptionOrNull()?.let { failure ->
            if (failure !is AddonNotFound) {
                DebugLog.w("${config.displayName} stream failed: ${failure.message}")
            }


            return null
        }
        val answer = result.getOrNull() ?: return null
        return answer.toSourceStream(config.id, request)
    }







    fun release() = client.clear()


    fun clearCompletedTrackCalls() = client.clearCompletedTrackCalls()
}











internal data class AddonRow(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val artworkUrl: String?,
    val codec: String?,
    val kbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val explicit: Boolean,
    val lossless: Boolean,
) {








    fun rank(request: StreamRequest?): Int {
        val losslessRow = lossless || codec?.lowercase() in LOSSLESS_CODECS
        return when {
            request is StreamRequest.Lossless -> if (losslessRow) 10_000 else kbps ?: 0
            losslessRow && request !is StreamRequest.Capped -> 10_000 + (kbps ?: 0)
            else -> kbps ?: 0
        }
    }

    fun toSong(configId: String): Song = Song(


        videoId = SourceRegistry.trackKey(configId, id),
        title = title,
        artist = artist,
        thumbnailUrl = artworkUrl,
        durationText = mmss(durationSec),
        albumName = album,
        sourceQuality = codec?.uppercase() ?: if (lossless) "LOSSLESS" else null,
    )

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
    }
}

internal fun AddonTrack.asRow(): AddonRow = AddonRow(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationSec = effectiveDurationSec,
    artworkUrl = effectiveArtworkUrl,
    codec = effectiveCodec,
    kbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    explicit = explicit,
    lossless = claimsLossless,
)

private fun List<AddonTrack>.asRows(): List<AddonRow> = map { it.asRow() }









internal fun AddonStream.toSourceStream(
    configId: String,
    request: StreamRequest,
): SourceStream? {

    if (url.isBlank() || isEncrypted) return null
    val claimedCodec = effectiveCodec?.lowercase()
    val claimedLossless = claimedCodec != null && claimedCodec in LOSSLESS_CODECS
    val servedKbps = effectiveBitrateKbps ?: 0
    val cap = request.kbpsCeiling
    val askedLossless = request is StreamRequest.Lossless
    val overCap = cap != null && servedKbps > cap
    return SourceStream(
        url = url,
        format = StreamFormat(
            codec = effectiveCodec,
            kbps = effectiveBitrateKbps,
            sampleRateHz = effectiveSampleRateHz,
            bitDepth = bitDepth,
        ),
        headers = headers,
        belowRequest = belowRequest || (askedLossless && !claimedLossless) || overCap,
        durationSec = durationSec,
        sourceConfigId = configId,
    )
}

private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
