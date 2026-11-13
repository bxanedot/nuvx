package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.sources.addon.AddonRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
























object SourceResolverBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    fun interface StreamCallback {
        fun onResult(json: String?, message: String?)
    }

    fun interface AnswerCallback {
        fun onResult(answer: Boolean)
    }











    fun canSubstituteForYouTube(): Boolean = runCatching { SourceResolver.canSubstituteForYouTube() }.getOrDefault(false)


    fun worthSwapping(candidateJson: String, playingJson: String?): Boolean {
        val candidate = readFormat(candidateJson) ?: return false
        val playing = playingJson?.let { readFormat(it) }
        return SourceResolver.worthSwapping(candidate, playing)
    }


    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?): Boolean =
        SourceResolver.sameRecordingAs(candidateSec, playingSec)




    fun substituteForYouTube(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isExplicit: Boolean?,
        isVideo: Boolean,
        callback: StreamCallback,
    ) = lookup(callback) {
        val target = TrackMatcher.Target(title, artist, durationSec, album, isExplicit, isVideo)
        SourceResolver.substituteForYouTube(target)
    }









    fun resolveAddonTrack(
        key: String,
        title: String,
        artist: String,
        durationSec: Int?,
        callback: StreamCallback,
    ) = lookup(callback) {
        val parsed = SourceRegistry.parseTrackKey(key) ?: return@lookup null
        val target = TrackMatcher.Target(title, artist, durationSec)
        SourceResolver.resolve(parsed.first, parsed.second, target)
    }











    fun upgradeFor(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isExplicit: Boolean?,
        isVideo: Boolean,
        playingJson: String?,
        servedBy: String?,
        callback: StreamCallback,
    ) = lookup(callback) {
        val target = TrackMatcher.Target(title, artist, durationSec, album, isExplicit, isVideo)
        val playing = playingJson?.let { readFormat(it) }
        SourceResolver.upgradeFor(target, playing, servedBy)
    }








    fun prefetchSubstitute(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isExplicit: Boolean?,
        isVideo: Boolean,
        callback: StreamCallback,
    ) = lookup(callback) {
        val target = TrackMatcher.Target(title, artist, durationSec, album, isExplicit, isVideo)
        SourceResolver.prefetchSubstitute(target)
    }


    fun forDownload(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isExplicit: Boolean?,
        isVideo: Boolean,
        quality: String,
        callback: StreamCallback,
    ) = launch(callback) {
        val target = TrackMatcher.Target(title, artist, durationSec, album, isExplicit, isVideo)
        val request = SourceResolver.requestForDownload(
            com.bxane.nuvx.data.settings.DownloadQuality.fromName(quality),
        )
        SourceResolver.forDownload(target, request)?.let { callback.onResult(it.encode(), null) }
            ?: callback.onResult(null, null)
    }











    fun activeCeiling(): String = ceiling(AppSettings.meteredConnection.value).name









    fun activeConnectionIsMetered(): Boolean = AppSettings.meteredConnection.value == true


    fun activeCeilingPermits(kind: String): Boolean {
        val name = ceiling(AppSettings.meteredConnection.value)
        val sourceKind = SourceKind.entries.firstOrNull { it.name == kind } ?: return true
        return name.permits(sourceKind)
    }

    private fun ceiling(metered: Boolean?): AudioQuality =
        if (metered == true) AppSettings.audioQualityCellular.value
        else AppSettings.audioQualityWifi.value











    fun permits(quality: String, kind: String): Boolean {
        val ceiling = when (quality) {
            "LOW" -> AudioQuality.LOW
            "MEDIUM" -> AudioQuality.MEDIUM
            "HIGH" -> AudioQuality.HIGH
            else -> AudioQuality.LOSSLESS
        }
        val sourceKind = SourceKind.entries.firstOrNull { it.name == kind } ?: return true
        return ceiling.permits(sourceKind)
    }



    fun interface ConfigsCallback {
        fun onResult(json: String?)
    }

    fun interface HealthCallback {
        fun onResult(configId: String, health: String, detail: String)
    }

    fun interface ActionCallback {
        fun onResult(ok: Boolean, message: String?)
    }


    fun configs(callback: ConfigsCallback) {
        val document = runCatching { json.encodeToString(ConfigListing.serializer(), readConfigs()) }.getOrNull()
        callback.onResult(document)
    }


    fun health(configId: String, callback: HealthCallback) {
        scope.launch {
            val health = try {
                withTimeoutOrNull(15_000) {
                    val config = SourceRegistry.config(configId)
                    if (config != null) SourceRegistry.probeCandidate(config)
                    else com.bxane.nuvx.data.sources.SourceHealth.Rejected("Unknown source")
                } ?: com.bxane.nuvx.data.sources.SourceHealth.Unreachable("The source did not answer in time. Try again.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                com.bxane.nuvx.data.sources.SourceHealth.Unreachable("The source could not be checked. Try again.")
            }
            callback.onResult(configId, healthName(health), healthDetail(health))
        }
    }









    fun probeCandidate(
        kind: String,
        baseUrl: String,
        label: String,
        callback: ActionCallback,
    ) = probeCandidateWithRefresh(kind, baseUrl, label, false, "", callback)

    fun probeCandidateWithRefresh(
        kind: String, baseUrl: String, label: String,
        refreshEnabled: Boolean, refreshUrl: String, callback: ActionCallback,
    ) {
        scope.launch {
            val resolved = SourceKind.entries.firstOrNull { it.name == kind }
            if (resolved == null) {
                callback.onResult(false, "Unknown kind \"$kind\"")
                return@launch
            }
            val health = SourceRegistry.probeCandidate(
                SourceConfig(
                    id = "candidate",
                    kind = resolved,
                    label = label,
                    baseUrl = baseUrl,
                    enabled = true,
                    refreshEnabled = refreshEnabled,
                    refreshUrl = refreshUrl,
                ),
            )



            callback.onResult(
                health is com.bxane.nuvx.data.sources.SourceHealth.Ok,
                healthDetail(health),
            )
        }
    }








    fun save(
        id: String,
        kind: String,
        label: String,
        baseUrl: String,
        enabled: Boolean,
        callback: ActionCallback,
    ) {
        val existing = try { SourceRegistry.config(id) } catch (_: Exception) {
            callback.onResult(false, "Sources could not be read. Unlock your device or Keychain and try again.")
            return
        }
        saveWithRefresh(id, kind, label, baseUrl, enabled,
            existing?.refreshEnabled ?: false, existing?.refreshUrl.orEmpty(), existing?.refreshUrlUnavailable ?: false, callback)
    }

    fun saveWithRefresh(
        id: String, kind: String, label: String, baseUrl: String, enabled: Boolean,
        refreshEnabled: Boolean, refreshUrl: String, preserveUnavailableRefreshUrl: Boolean = false, callback: ActionCallback,
    ) {
        scope.launch {
            try {
                val preserveLink = preserveUnavailableRefreshUrl && refreshUrl.isBlank()
                if (refreshEnabled && (kind != "ADDON" || (!preserveLink && !AddonRefresh.validAddress(refreshUrl.trim())))) {
                    callback.onResult(false, "Enter an HTTPS renewal link from the addon provider")
                    return@launch
                }
                val resolved = SourceKind.entries.firstOrNull { it.name == kind }
                if (resolved == null) {
                    callback.onResult(false, "Unknown kind \"$kind\"")
                    return@launch
                }
                val config = SourceConfig(
                    id = id,
                    kind = resolved,
                    label = label,
                    baseUrl = baseUrl,
                    enabled = enabled,
                    refreshEnabled = refreshEnabled,
                    refreshUrl = refreshUrl,
                    refreshUrlUnavailable = preserveLink,
                )
                if (SourceRegistry.config(id) == null) {
                    SourceRegistry.add(config)
                } else {
                    SourceRegistry.update(config)
                }
                callback.onResult(true, null)
            } catch (e: Exception) {
                DebugLog.e("saving a source failed", e)
                callback.onResult(false, e.message ?: e.toString())
            }
        }
    }








    fun rankOf(kind: String): Int =
        SourceKind.entries.firstOrNull { it.name == kind }?.rank ?: Int.MAX_VALUE






    fun addonCount(): Int = runCatching { SourceRegistry.active().count { it is AddonSource } }.getOrDefault(0)

    fun setEnabled(configId: String, enabled: Boolean, callback: ActionCallback) {
        sourceAction(callback) { SourceRegistry.setEnabled(configId, enabled) }
    }

    fun remove(configId: String, callback: ActionCallback) {
        sourceAction(callback) { SourceRegistry.remove(configId) }
    }


    fun reorderAddons(orderedIds: List<String>, callback: ActionCallback) {
        sourceAction(callback) { SourceRegistry.reorderAddons(orderedIds) }
    }


    internal fun sourceAction(callback: ActionCallback, action: () -> Unit) {
        val error = try {
            action()
            null
        } catch (_: Exception) {
            "Could not save source settings; unlock your device or Keychain and try again"
        }
        callback.onResult(error == null, error)
    }








    fun isDuplicate(url: String, exceptId: String?): Boolean =
        runCatching { SourceRegistry.duplicateOf(url, exceptId) != null }.getOrDefault(false)


    fun refreshPlaybackSources() {
        runCatching {
            SourceRegistry.activeForPlayback().filterIsInstance<AddonSource>().forEach { it.clearCompletedTrackCalls() }
        }
    }

    private fun lookup(callback: StreamCallback, block: suspend () -> SourceStream?) {
        scope.launch {
            val report = SourceResolutionReport()
            try {
                val stream = withContext(report) { block() }
                callback.onResult(stream?.encode(), report.summary())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { callback.onResult(null, "Preferred sources could not provide playable audio.") }
        }
    }


    private fun launch(callback: StreamCallback, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("source resolution failed", e)
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }



    private fun healthName(health: com.bxane.nuvx.data.sources.SourceHealth): String =
        when (health) {
            is com.bxane.nuvx.data.sources.SourceHealth.Ok -> "ok"
            is com.bxane.nuvx.data.sources.SourceHealth.Unreachable -> "unreachable"
            is com.bxane.nuvx.data.sources.SourceHealth.Rejected -> "rejected"
        }

    private fun healthDetail(health: com.bxane.nuvx.data.sources.SourceHealth): String =
        when (health) {
            is com.bxane.nuvx.data.sources.SourceHealth.Ok -> health.detail.orEmpty()
            is com.bxane.nuvx.data.sources.SourceHealth.Unreachable -> health.reason
            is com.bxane.nuvx.data.sources.SourceHealth.Rejected -> health.reason
        }

    private fun readFormat(document: String): StreamFormat? =
        runCatching { json.decodeFromString(FormatDocument.serializer(), document) }
            .getOrNull()
            ?.toFormat()

    private fun SourceStream.encode(): String = encodeStream(this)



    internal fun encodeStream(stream: SourceStream): String = json.encodeToString(
        StreamDocument.serializer(),
        StreamDocument(
            url = stream.url,
            format = FormatDocument(
                codec = stream.format.codec,
                kbps = stream.format.kbps,
                sampleRateHz = stream.format.sampleRateHz,
                bitDepth = stream.format.bitDepth,
                verified = stream.format.verified,
                isLossless = stream.format.isLossless,
                isDolbyAtmos = stream.format.isDolbyAtmos,
                summary = stream.format.summary,
            ),
            headers = stream.headers,
            belowRequest = stream.belowRequest,
            durationSec = stream.durationSec,
            sourceConfigId = stream.sourceConfigId,
        ),
    )

    private fun readConfigs(): ConfigListing = ConfigListing(
            SourceRegistry.configs.value.map { config ->
                ConfigDocument(
                    id = config.id,
                    kind = config.kind.name,
                    label = config.label,
                    displayName = config.displayName,


                    baseUrl = SourceRegistry.baseUrlOf(config),
                    enabled = config.enabled,
                    isComplete = config.isComplete,
                    needsServer = config.kind.needsServer,
                    canServeLossless = config.kind.canServeLossless,
                    labels = config.kind.labels,
                    refreshEnabled = config.refreshEnabled,
                    refreshUrl = config.refreshUrl,
                    refreshUrlUnavailable = config.refreshUrlUnavailable,
                )
            },
        )
}









@Serializable
data class FormatDocument(
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val verified: Boolean = false,
    val isLossless: Boolean? = null,
    val isDolbyAtmos: Boolean = false,
    val summary: String = "",
)


@Serializable
data class StreamDocument(
    val url: String,
    val format: FormatDocument,
    val headers: Map<String, String> = emptyMap(),
    val belowRequest: Boolean = false,
    val durationSec: Int? = null,
    val sourceConfigId: String? = null,
)


@Serializable
data class ConfigDocument(
    val id: String,
    val kind: String,
    val label: String,
    val displayName: String,
    val baseUrl: String,
    val enabled: Boolean,
    val isComplete: Boolean,
    val needsServer: Boolean,
    val canServeLossless: Boolean,
    val labels: List<String>,
    val refreshEnabled: Boolean = false,
    val refreshUrl: String = "",
    val refreshUrlUnavailable: Boolean = false,
)

@Serializable
data class ConfigListing(val sources: List<ConfigDocument>)

private fun FormatDocument.toFormat(): StreamFormat = StreamFormat(
    codec = codec,
    kbps = kbps,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    verified = verified,
)
