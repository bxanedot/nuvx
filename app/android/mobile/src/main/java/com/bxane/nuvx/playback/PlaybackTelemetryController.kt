package com.bxane.nuvx.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.TelemetryProvenance
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlaybackTelemetryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val resolveLocalUri: (String, MediaItem?) -> Uri?,
) {
    private val localBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuredBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuringBitrate: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun forgetLastSession() {
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrateCache.clear()
    }

    fun onTrackTransition() {
        measuredBitrateCache.clear()
    }

    fun clear() {
        localBitrateCache.clear()
        measuredBitrateCache.clear()
    }

    fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val kbps = runCatching {
                val retriever = MediaMetadataRetriever()
                var bps: Long? = null
                var durMs: Long? = null
                try {
                    retriever.setDataSource(context, uri)
                    bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }

                if (bps != null && bps > 0) {
                    ((bps + 500L) / 1000L).toInt()
                } else if (durMs != null && durMs > 0) {
                    val sizeBytes = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.File(it).length() }
                        "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        else -> null
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        ((sizeBytes * 8000L) / durMs / 1000L).toInt().takeIf { it > 0 }
                    } else null
                } else null
            }.getOrNull()

            if (kbps != null && kbps > 0) {
                localBitrateCache[mediaId] = kbps
                withContext(Dispatchers.Main) {
                    if (player()?.currentMediaItem?.mediaId == mediaId) {
                        publish()
                    }
                }
            }
        }
    }

    fun publish() {
        val activePlayer = player() ?: return
        val format = activePlayer.audioFormat
        val mediaId = activePlayer.currentMediaItem?.mediaId
        val generation = NerdStats.currentGeneration
        val measured = format?.measure()
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val (bitrate, provenance) = when {
            format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            mediaId != null && localBitrateCache.containsKey(mediaId) ->
                Pair(localBitrateCache[mediaId], TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                NerdStats.sourceBitrate(mediaId) { measuredStreamKbps(activePlayer) }
                    ?: Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.publish(NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, activePlayer.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
            mediaId = mediaId,
        ), expectedGeneration = generation)
    }

    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return true
        }
        if (Downloads.verifiedSavedUri(mediaId) != null) {
            return true
        }
        return false
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }

        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null) {
            QualityUpgrade.forcedStream(uri)?.let { upgraded ->
                val name = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                if (!name.isNullOrBlank()) return name
            }
        }

        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (sourceTrack != null) {
            val config = SourceRegistry.config(sourceTrack.first)
            if (config != null) return config.displayName
            return "Unknown"
        }

        StreamChoice.of(id)?.let { serving ->
            val name = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(id)) "Module" else null
            if (!name.isNullOrBlank()) return name
        }

        if (uri?.authority == "source") {
            val configId = uri.getQueryParameter("s")
            val name = configId?.let { SourceRegistry.config(it)?.displayName }
            if (!name.isNullOrBlank()) return name
            return "Unknown"
        }

        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        if (id.length == 11 || uri?.getQueryParameter("v") != null) {
            return "YouTube"
        }

        return "Unknown"
    }

    private fun measuredStreamKbps(activePlayer: Player): Int? {
        val uri = activePlayer.currentMediaItem?.localConfiguration?.uri ?: return null
        val key = uri.toString()
        measuredBitrateCache[key]?.let { return it }
        val durationMs = activePlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (measuringBitrate.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = AudioCache.contentLengthOf(uri)
                    if (bytes > 0) {
                        val kbps = (bytes * 8 / durationMs).toInt()
                        if (kbps > 0) measuredBitrateCache[key] = kbps
                    }
                } finally {
                    measuringBitrate.remove(key)
                }
            }
        }
        return null
    }
}
