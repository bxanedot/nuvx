package com.bxane.nuvx.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.bxane.nuvx.auth.EncryptedPrefs
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.permits
import com.bxane.nuvx.data.sources.addon.AddonAuth
import com.bxane.nuvx.data.sources.addon.AddonClient
import com.bxane.nuvx.data.sources.addon.AddonException
import com.bxane.nuvx.data.sources.addon.AddonQualityOption
import com.bxane.nuvx.data.sources.addon.DetectedFormat
import com.bxane.nuvx.data.sources.addon.SourceFormats
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.playback.audio.LosslessOutput
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.UUID

@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = kind != SourceKind.JIOSAAVN,
    val allowDownloads: Boolean = true,
    val checkValidLossless: Boolean = false,
    val qualityOptions: List<AddonQualityOption> = emptyList(),
    val pickedQuality: String? = null,
    val nuvxAuth: Boolean? = null,
) {
    val activeQuality: AddonQualityOption?
        get() = qualityOptions.firstOrNull { it.value == pickedQuality } ?: qualityOptions.firstOrNull()

    val awaitsLosslessOutput: Boolean
        get() = checkValidLossless && !LosslessOutput.capable

    val needsAddonUpdate: Boolean
        get() = kind == SourceKind.ADDON && nuvxAuth == false

    internal fun withPolicyOf(other: SourceConfig) =
        copy(
            allowDownloads = other.allowDownloads,
            checkValidLossless = other.checkValidLossless,
            qualityOptions = other.qualityOptions,
            nuvxAuth = other.nuvxAuth,
        )

    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }

    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

object SourceRegistry {

    private const val TAG = "nuvx"

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    private var instances: Map<String, MusicSource> = emptyMap()

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, "nuvx_sources", "nuvx_sources_plain")

        val stored = prefs.getString(KEY_SOURCES, null)?.let(::decodeStored) ?: emptyList()
        val jioOptInMigrationDone = prefs.getBoolean(KEY_JIOSAAVN_OPT_IN_V1, false)
        val after = sourcesForInit(stored, forceJioSaavnOff = !jioOptInMigrationDone)

        publish(after, persist = false)
        if (after != stored || !jioOptInMigrationDone) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), after))
                .putBoolean(KEY_JIOSAAVN_OPT_IN_V1, true)
                .apply()
        }
    }

    internal fun sourcesForInit(
        stored: List<SourceConfig>,
        forceJioSaavnOff: Boolean,
    ): List<SourceConfig> {
        val seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it) }

        return seeded
            .filterNot { it.kind == SourceKind.MODULE }
            .map { config ->
                when {
                    config.kind == SourceKind.YOUTUBE && !config.enabled -> config.copy(enabled = true)
                    config.kind == SourceKind.JIOSAAVN && forceJioSaavnOff -> config.copy(enabled = false)
                    else -> config
                }
            }
    }

    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    fun active(): List<MusicSource> {
        val unverified = AddonAuth.standingRefusal() != null
        return enabledConfigs(configs.value)
            .filterNot { it.awaitsLosslessOutput }
            .filterNot { unverified && it.kind == SourceKind.ADDON && it.nuvxAuth == true }
            .sortedBy { it.kind.rank }
            .mapNotNull { instances[it.id] }
    }

    fun activeForDownload(): List<MusicSource> =
        active().filter { config(it.configId)?.allowDownloads != false }

    enum class DownloadExclusion {
        DOWNLOADS_NOT_ALLOWED,

        NEEDS_LOSSLESS_OUTPUT,
    }

    fun addonsLeftOutOfDownloads(): List<Pair<SourceConfig, DownloadExclusion>> =
        enabledConfigs(configs.value)
            .filter { it.kind == SourceKind.ADDON }
            .mapNotNull { config ->
                when {
                    !config.allowDownloads -> config to DownloadExclusion.DOWNLOADS_NOT_ALLOWED
                    config.awaitsLosslessOutput -> config to DownloadExclusion.NEEDS_LOSSLESS_OUTPUT
                    else -> null
                }
            }

    fun updatePolicy(
        configId: String,
        allowDownloads: Boolean,
        checkValidLossless: Boolean,
        qualityOptions: List<AddonQualityOption>,
        nuvxAuth: Boolean,
    ) {
        val current = config(configId) ?: return
        if (current.allowDownloads == allowDownloads &&
            current.checkValidLossless == checkValidLossless &&
            current.qualityOptions == qualityOptions &&
            current.nuvxAuth == nuvxAuth
        ) {
            return
        }
        TrackLog.d(
            TAG,
            "${current.displayName}: manifest now says allowDownloads=$allowDownloads " +
                "checkValidLossless=$checkValidLossless qualityOptions=${qualityOptions.map { it.value }} " +
                "nuvxAuth=$nuvxAuth",
        )
        publish(
            configs.value.map {
                if (it.id == configId) {
                    it.copy(
                        allowDownloads = allowDownloads,
                        checkValidLossless = checkValidLossless,
                        qualityOptions = qualityOptions,
                        nuvxAuth = nuvxAuth,
                    )
                } else {
                    it
                }
            },
        )
    }

    fun markAddonOutdated(configId: String) {
        val current = config(configId) ?: return
        if (current.nuvxAuth == false) return
        TrackLog.d(TAG, "${current.displayName}: no usable manifest, so it can't declare nuvxAuth")
        publish(configs.value.map { if (it.id == configId) it.copy(nuvxAuth = false) else it })
    }

    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filter { it.enabled && it.isComplete }

    fun activeForPlayback(): List<MusicSource> {
        val ceiling = AppSettings.effectiveAudioQuality
        return active().filter { ceiling.permits(it.kind) }
    }

    fun permitsForPlayback(
        configId: String?,
        ceiling: AudioQuality = AppSettings.effectiveAudioQuality,
    ): Boolean {
        val kind = configId?.let { config(it)?.kind } ?: return true
        return ceiling.permits(kind)
    }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    fun clearCompletedAddonTrackCalls() {
        instances.values
            .filterIsInstance<AddonSource>()
            .forEach(AddonSource::clearCompletedTrackCalls)
    }

    fun add(config: SourceConfig) = publish(configs.value + config.tidied())

    fun update(config: SourceConfig) =
        config.tidied().let { tidied ->
            publish(configs.value.map { if (it.id == tidied.id) tidied else it })
        }

    private fun SourceConfig.tidied(): SourceConfig =
        if (kind == SourceKind.ADDON) copy(baseUrl = AddonClient.normalizeBase(baseUrl)) else this

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    fun reorderAddons(orderedIds: List<String>) {
        val addons = configs.value.filter { it.kind.isUserAdded }
        if (addons.size < 2) return
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.mapNotNull(byId::get)
        val missed = addons.filterNot { config -> moved.any { it.id == config.id } }
        val reordered = moved + missed
        if (reordered.map { it.id } == addons.map { it.id }) return
        publish(reordered + configs.value.filterNot { it.kind.isUserAdded })
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        configs.value = next
        val previous = instances
        instances = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        previous.values.filterNot { it in instances.values }
            .filterIsInstance<AddonSource>()
            .forEach { it.release() }
        if (persist && ::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), next))
                .apply()
        }
    }

    suspend fun identify(url: String, existing: SourceConfig? = null): Result<SourceConfig> {
        val detected = SourceFormats.identify(url).getOrElse { return Result.failure(it) }
        return when (detected) {
            is DetectedFormat.Addon -> Result.success(
                (existing ?: SourceConfig(kind = SourceKind.ADDON)).copy(
                    kind = SourceKind.ADDON,
                    baseUrl = detected.baseUrl,
                    label = detected.manifest.displayName,
                    allowDownloads = detected.manifest.downloadsAllowed,
                    checkValidLossless = detected.manifest.requiresLosslessOutput,
                    qualityOptions = detected.manifest.qualityOptions,
                    nuvxAuth = detected.manifest.requiresNuvxAuth,
                    pickedQuality = existing?.pickedQuality?.takeIf { picked ->
                        detected.manifest.qualityOptions.any { it.value == picked }
                    },
                ),
            )
            is DetectedFormat.ModuleIndex -> Result.success(
                (existing ?: SourceConfig(kind = SourceKind.CUSTOM_MODULE)).copy(
                    kind = SourceKind.CUSTOM_MODULE,
                    baseUrl = detected.url,
                    label = existing?.label.orEmpty(),
                    qualityOptions = emptyList(),
                    pickedQuality = null,
                ),
            )
            is DetectedFormat.Unsupported -> Result.failure(AddonException(detected.reason))
        }
    }

    fun duplicateOf(url: String, exceptId: String? = null): SourceConfig? {
        val wanted = canonicalUrl(url)
        if (wanted.isEmpty()) return null
        return configs.value.firstOrNull { it.id != exceptId && canonicalUrl(it.baseUrl) == wanted }
    }

    private fun canonicalUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
        val path = parsed.encodedPath.trimEnd('/')
        val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        return "${parsed.scheme}://${parsed.host}${if (parsed.port != defaultPort(parsed.scheme)) ":${parsed.port}" else ""}$path$query"
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    suspend fun probeCandidate(config: SourceConfig): SourceHealth = build(config).health()

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.ADDON -> AddonSource(config)
        SourceKind.CUSTOM_MODULE -> ModuleSource(config)
        SourceKind.MODULE -> ModuleSource(config)
        SourceKind.JIOSAAVN -> JioSaavnSource(config)
        SourceKind.YOUTUBE -> YouTubeSource(config)
    }

    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config.withPolicyOf(config) == config

    internal interface ConfigBacked {
        val config: SourceConfig
    }

    fun trackKey(configId: String, trackId: String) = "$PREFIX$configId$SEPARATOR$trackId"

    fun parseTrackKey(key: String): Pair<String, String>? = SourceTrackKeys.parse(key)

    fun trackUri(configId: String, trackId: String): String =
        Uri.Builder()
            .scheme("nuvx")
            .authority("source")
            .appendQueryParameter("s", configId)
            .appendQueryParameter("t", trackId)
            .build()
            .toString()

    private val BUILT_IN_KINDS = listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE)

    private const val KEY_SOURCES = "sources"
    private const val KEY_JIOSAAVN_OPT_IN_V1 = "jiosaavn_opt_in_v1"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}
