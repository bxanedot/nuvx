package com.bxane.nuvx.data.sources

import android.util.Log
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.module.ModuleManager
import com.bxane.nuvx.data.sources.module.ModuleSearchResult
import com.bxane.nuvx.data.sources.module.SpineModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

class ModuleSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MODULE
    override val displayName: String get() = config.displayName

    private val manager = ModuleManager()

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) {
            return@withContext SourceHealth.Rejected("A module index URL is required")
        }
        try {
            val modules = manager.fetchIndex(config.baseUrl).getOrThrow()
            when {
                modules.isEmpty() -> SourceHealth.Rejected(
                    "The index answered but listed no modules — check the URL"
                )
                else -> SourceHealth.Ok("${modules.size} module${if (modules.size == 1) "" else "s"}")
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "module index fetch failed for ${config.displayName}: ${e.message}")
            SourceHealth.Unreachable(e.message ?: "Could not reach the module index")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val indexUrl = config.baseUrl
            val baseUrl = indexUrl.substringBeforeLast("/")

            val modules = manager.fetchIndex(indexUrl).getOrElse { e ->
                TrackLog.w(TAG, "${config.displayName}: index fetch failed — ${e.message}")
                return@withContext emptyList()
            }

            val perModule = coroutineScope {
                val answers = arrayOfNulls<List<Song>>(modules.size)
                val first = CompletableDeferred<Unit>()
                val jobs = modules.mapIndexed { at, module ->
                    launch {
                        val songs = searchOne(module, query, limit, baseUrl)
                        answers[at] = songs
                        if (songs.isNotEmpty()) first.complete(Unit)
                    }
                }
                if (waitForAll) {
                    withTimeoutOrNull(SEARCH_PATIENT_MS) {
                        withTimeoutOrNull(SEARCH_BUDGET_MS) { first.await() }
                        withTimeoutOrNull(SEARCH_PATIENT_GRACE_MS) { jobs.joinAll() }
                    }
                } else {
                    withTimeoutOrNull(SEARCH_BUDGET_MS) { first.await() }
                    withTimeoutOrNull(SEARCH_GRACE_MS) { jobs.joinAll() }
                }
                jobs.forEach { it.cancel() }
                answers.filterNotNull()
            }
            interleave(perModule).take(limit)
        }

    private suspend fun searchOne(
        module: SpineModule,
        query: String,
        limit: Int,
        baseUrl: String,
    ): List<Song> {
        val loaded = manager.loadModule(module) { baseUrl }.getOrElse { e ->
            TrackLog.w(TAG, "${config.displayName}: load failed for ${module.id} — ${e.message}")
            return emptyList()
        }
        val searchResponse = manager.searchTracks(loaded, query, limit).getOrElse { e ->
            TrackLog.w(TAG, "${config.displayName}: search failed for ${module.id} — ${e.message}")
            return emptyList()
        }
        return searchResponse.tracks.map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, "${module.id}$MOD_SEPARATOR${track.id}"),
                title = track.title,
                artist = track.artist,
                albumName = track.album.ifBlank { null },
                thumbnailUrl = track.albumCover,
                durationText = track.duration.takeIf { it > 0 }
                    ?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
                sourceQuality = rowTier(track),
            )
        }
    }

    private fun rowTier(track: ModuleSearchResult): String? =
        qualityTier("${track.audioQuality} ${track.format}")
            ?: track.availableQualities
                .maxByOrNull { TIERS.indexOf(qualityTier(it)) }
                ?.let(::qualityTier)

    private fun interleave(lists: List<List<Song>>): List<Song> {
        val merged = mutableListOf<Song>()
        var rank = 0
        while (lists.any { it.size > rank }) {
            for (list in lists) list.getOrNull(rank)?.let(merged::add)
            rank++
        }
        return merged
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val cut = trackId.indexOf(MOD_SEPARATOR)
            if (cut < 0) {
                TrackLog.w(TAG, "${config.displayName}: malformed trackId '$trackId'")
                return@withContext null
            }
            val moduleId = trackId.substring(0, cut)
            val upstreamId = trackId.substring(cut + MOD_SEPARATOR.length)

            val modules = manager.fetchIndex(config.baseUrl).getOrElse { e ->
                TrackLog.w(TAG, "${config.displayName}: index fetch failed — ${e.message}")
                return@withContext null
            }
            val module = modules.firstOrNull { it.id == moduleId } ?: run {
                TrackLog.w(TAG, "${config.displayName}: module '$moduleId' not found in index")
                return@withContext null
            }
            val baseUrl = config.baseUrl.substringBeforeLast("/")
            val loaded = manager.loadModule(module) { baseUrl }.getOrElse { e ->
                TrackLog.w(TAG, "${config.displayName}: load failed for $moduleId — ${e.message}")
                return@withContext null
            }
            val streamResponse = manager.getStreamUrl(
                loaded = loaded,
                trackId = upstreamId,
                quality = request.tier,
                settings = settingsFor(request),
            ).getOrElse { e ->
                TrackLog.w(TAG, "${config.displayName}: getStreamUrl failed for $upstreamId — ${e.message}")
                return@withContext null
            }
            val url = streamResponse.streamUrl?.ifBlank { null } ?: run {
                TrackLog.w(TAG, "${config.displayName}: empty stream URL for $upstreamId")
                return@withContext null
            }
            if (malformed(url)) {
                TrackLog.w(
                    TAG,
                    "${config.displayName}: $moduleId returned a malformed URL for " +
                        "$upstreamId; skipping it — ${url.take(120)}",
                )
                return@withContext null
            }

            val trackMeta = streamResponse.track
            val format = StreamFormat(
                codec = codecOf(trackMeta?.mimeType, trackMeta?.audioQuality, url),
                kbps = trackMeta?.bitrate ?: kbpsFor(trackMeta?.audioQuality, url),
                sampleRateHz = trackMeta?.sampleRate?.let {
                    if (it < 1000) (it * 1000).toInt() else it.toInt()
                }?.takeIf { it > 0 },
                bitDepth = trackMeta?.bitDepth?.takeIf { it > 0 },
                kbpsInferred = trackMeta?.bitrate == null,
            )
            if (unplayable(format, atmosAllowed = DeviceCodecs.atmosWanted)) {
                val why = DeviceCodecs.atmosRefusal()
                TrackLog.w(
                    TAG,
                    "${config.displayName}: $moduleId answered a ${request.tier} request with " +
                        "${format.summary}, $why — passing",
                )
                return@withContext null
            }
            SourceStream(url = url, format = format)
        }

    private fun codecOf(mimeType: String?, quality: String?, url: String): String? {
        mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val qualityText = quality.orEmpty().uppercase(Locale.ROOT)
        if ("ATMOS" in qualityText || "EAC3_JOC" in qualityText || "EC-3" in qualityText) return "eac3-joc"
        if (qualityTier(quality.orEmpty()) == LOSSLESS) return "flac"
        return url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)
            .takeIf { it in AUDIO_EXTENSIONS }
    }

    private fun kbpsFor(quality: String?, url: String): Int? =
        kbpsIn(quality) ?: kbpsIn(url) ?: when (qualityTier(quality.orEmpty())) {
            HIGH -> 320
            LOW -> 128
            else -> null
        }

    private fun kbpsIn(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val found = KBPS_LABEL.find(text) ?: KBPS_URL.find(text) ?: return null
        return found.groupValues[1].toIntOrNull()?.takeIf { it in 8..2_000 }
    }

    private fun settingsFor(request: StreamRequest): Map<String, String> = mapOf(
        "quality" to request.tier,
        "fallbackMode" to if (request is StreamRequest.Lossless) "strict" else "flexible",
        "dolbyAtmos" to DeviceCodecs.atmosWanted.toString(),
    )

    private val StreamRequest.tier: String
        get() = when (this) {
            is StreamRequest.Lossless -> LOSSLESS
            is StreamRequest.Best -> HIGH
            is StreamRequest.Capped -> if (maxKbps <= LOW_CEILING_KBPS) LOW else HIGH
        }

    internal companion object {
        const val TAG = "nuvx"

        fun malformed(url: String): Boolean {
            if (url.toHttpUrlOrNull() == null) return true
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return false
            val originEnd = url.indexOf('/', schemeEnd + 3)
            if (originEnd < 0) return false
            return url.indexOf(url.substring(0, originEnd), originEnd) >= 0
        }

        internal fun unplayable(format: StreamFormat, atmosAllowed: Boolean): Boolean =
            format.isDolbyAtmos && !atmosAllowed

        const val LOSSLESS = "LOSSLESS"
        const val HIGH = "HIGH"
        const val LOW = "LOW"

        const val DOLBY = "DOLBY"

        val TIERS = listOf(LOW, HIGH, LOSSLESS)

        fun qualityTier(label: String): String? {
            val text = label.uppercase(Locale.ROOT)
            return when {
                text.isBlank() -> null
                LOSSLESS_HINTS.any { it in text } -> LOSSLESS
                LOW_HINTS.any { it in text } -> LOW
                HIGH_HINTS.any { it in text } -> HIGH
                else -> null
            }
        }

        private val LOSSLESS_HINTS =
            listOf("LOSSLESS", "FLAC", "ALAC", "HI-RES", "HI_RES", "HIRES", "24-BIT", "16-BIT", "WAV")
        private val LOW_HINTS = listOf("LOW", "128", "96KBPS", "64")
        private val HIGH_HINTS = listOf("HIGH", "320", "MP3", "AAC", "M4A", "OPUS", "OGG")

        const val LOW_CEILING_KBPS = 128

        private val AUDIO_EXTENSIONS =
            setOf("flac", "alac", "wav", "aiff", "mp3", "m4a", "aac", "ogg", "opus", "webm")

        private val KBPS_LABEL = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE)
        private val KBPS_URL = Regex("""\.(\d{2,4})\.(?:mp3|m4a|aac|ogg)""", RegexOption.IGNORE_CASE)

        const val SEARCH_BUDGET_MS = 8_000L
        const val SEARCH_GRACE_MS = 2_500L

        const val SEARCH_PATIENT_MS = 25_000L

        const val SEARCH_PATIENT_GRACE_MS = 8_000L

        const val MOD_SEPARATOR = "::"
    }
}
