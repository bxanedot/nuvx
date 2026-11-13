package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.PlatformSettings
import com.bxane.nuvx.data.sources.addon.AddonClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.random.Random






















object SourceRegistry {

    private const val TAG = "nuvx"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshStorage = SourceRefreshStorage(PlatformSettings::readSecret, PlatformSettings::writeSecret)
    private val listStorage = SourceListStorage(
        readPrimary = { PlatformSettings.getString(KEY_SOURCES, "") },
        writePrimary = { PlatformSettings.putString(KEY_SOURCES, it) },
        readBackup = { PlatformSettings.readSecret("sources-document-backup-v1") },
        writeBackup = { PlatformSettings.writeSecret("sources-document-backup-v1", it) },
        isValid = ::isReadableDocument,
    )


    private val _configs = MutableStateFlow<List<SourceConfig>>(emptyList())
    private val configFlow: StateFlow<List<SourceConfig>> = _configs.asStateFlow()
    val configs: StateFlow<List<SourceConfig>>
        get() {
            init()
            restoreUnavailableRefreshUrls()
            return configFlow
        }








    @Volatile
    private var instances: Map<String, MusicSource> = emptyMap()


    private val restored: Unit by lazy { restore() }

    fun init() { restored }

    private fun restore() {
        val stored = readStored()
        val jioOptInDone = PlatformSettings.getBoolean(KEY_JIOSAAVN_OPT_IN_V1, false)
        val after = sourcesForInit(
            stored,
            forceJioSaavnOff = !jioOptInDone,
            legacyModuleIndexUrl = AppSettings.moduleIndexUrl.value,
        )




        if (after != stored || !jioOptInDone) {
            writeStored(after)
            PlatformSettings.putBoolean(KEY_JIOSAAVN_OPT_IN_V1, true)
        }
        publish(after, persist = false)
    }









    internal fun sourcesForInit(
        stored: List<SourceConfig>,
        forceJioSaavnOff: Boolean,
        legacyModuleIndexUrl: String = "",
    ): List<SourceConfig> {



        val seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it) }









        val withLegacy = if (
            legacyModuleIndexUrl.isNotBlank() && seeded.none { it.kind == SourceKind.CUSTOM_MODULE }
        ) {
            seeded + SourceConfig(
                id = "migrated-module-index",
                kind = SourceKind.CUSTOM_MODULE,
                label = "",
                baseUrl = legacyModuleIndexUrl.trim().trimEnd('/'),
            )
        } else {
            seeded
        }




        return withLegacy
            .filterNot { it.kind == SourceKind.MODULE }
            .map { config ->
                when {
                    config.kind == SourceKind.YOUTUBE && !config.enabled -> config.copy(enabled = true)
                    config.kind == SourceKind.JIOSAAVN && forceJioSaavnOff -> config.copy(enabled = false)
                    else -> config
                }
            }
    }








    private fun readStored(): List<SourceConfig> {
        val raw = listStorage.read()
        if (raw.isBlank()) return emptyList()
        val elements = json.parseToJsonElement(raw).jsonArray
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { DebugLog.w("Skipping an unreadable stored source entry") }
                .getOrNull()?.let { config ->
                    refreshStorage.restore(config)
                }
        }
    }

    internal fun isReadableDocument(raw: String): Boolean = runCatching {
        val elements = json.parseToJsonElement(raw).jsonArray
        val configs = mutableListOf<SourceConfig>()
        for (element in elements) {
            val fields = element.jsonObject
            val kind = fields["kind"]?.jsonPrimitive?.content ?: return@runCatching false


            if (SourceKind.entries.none { it.name == kind }) continue
            if (fields["id"]?.jsonPrimitive?.content.isNullOrBlank()) return@runCatching false
            configs += json.decodeFromJsonElement(SourceConfig.serializer(), element)
        }
        (elements.isEmpty() || configs.isNotEmpty()) &&
            configs.all { it.id.isNotBlank() } && configs.map { it.id }.distinct().size == configs.size
    }.getOrDefault(false)

    private fun writeStored(configs: List<SourceConfig>) {
        refreshStorage.commit(configs, _configs.value) {
            listStorage.write(
                json.encodeToString(ListSerializer(SourceConfig.serializer()), configs.map { it.copy(refreshUrl = "") }),
            )
        }
    }

    private fun restoreUnavailableRefreshUrls() {
        val before = _configs.value
        if (before.none { it.refreshUrlUnavailable }) return
        val after = before.map { if (it.refreshUrlUnavailable) refreshStorage.restore(it) else it }
        if (before != after) publish(after, persist = false)
    }













    fun active(): List<MusicSource> {
        init()
        restoreUnavailableRefreshUrls()
        return enabledConfigs(_configs.value)
            .sortedBy { it.kind.rank }
            .mapNotNull { instances[it.id] }
    }


    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filter { it.enabled && it.isComplete }












    fun activeForPlayback(): List<MusicSource> {
        init()
        val ceiling = AppSettings.effectiveAudioQualityFlow.value
        return active().filter { ceiling.permits(it.kind) }
    }

    fun instance(configId: String): MusicSource? {
        init()
        restoreUnavailableRefreshUrls()
        return instances[configId]
    }

    fun config(configId: String): SourceConfig? {
        init()
        restoreUnavailableRefreshUrls()
        return _configs.value.firstOrNull { it.id == configId }
    }



    fun add(config: SourceConfig) {
        init()
        publish(_configs.value + config.tidied())
    }

    fun update(config: SourceConfig) {
        init()
        val previous = this.config(config.id)
        val tidied = config.tidied().let {
            if (previous?.refreshUrlUnavailable == true && it.refreshUrl.isBlank()) it.copy(refreshUrlUnavailable = true) else it
        }
        publish(_configs.value.map { if (it.id == tidied.id) tidied else it })
    }











    private fun SourceConfig.tidied(): SourceConfig =
        if (kind == SourceKind.ADDON) copy(baseUrl = normalizeAddonBase(baseUrl), refreshUrl = refreshUrl.trim()) else this

    fun remove(configId: String) {
        val target = config(configId) ?: return



        if (target.kind in BUILT_IN_KINDS) return

        publish(_configs.value.filterNot { it.id == configId })
        PlatformSettings.putSecret(secretAccount(target.id), null)
    }











    fun setEnabled(configId: String, enabled: Boolean) {
        init()
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(_configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }
















    fun reorderAddons(orderedIds: List<String>) {
        init()
        val addons = _configs.value.filter { it.kind.isUserAdded }
        if (addons.size < 2) return
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.mapNotNull(byId::get)
        val missed = addons.filterNot { config -> moved.any { it.id == config.id } }
        val reordered = moved + missed
        if (reordered.map { it.id } == addons.map { it.id }) return
        publish(reordered + _configs.value.filterNot { it.kind.isUserAdded })
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        if (persist) writeStored(next)
        _configs.value = next



        val previous = instances
        instances = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
    }










    suspend fun probeCandidate(config: SourceConfig): SourceHealth = build(config.tidied()).health()

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.ADDON -> AddonSource(config)

        SourceKind.CUSTOM_MODULE -> ModuleSource(config)
        SourceKind.MODULE -> ModuleSource(config)
        SourceKind.JIOSAAVN -> JioSaavnSource(config)
        SourceKind.YOUTUBE -> YouTubeSource(config)
    }






    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config == config












    fun trackKey(configId: String, trackId: String): String = "$PREFIX$configId$SEPARATOR$trackId"


    fun parseTrackKey(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val cut = body.indexOf(SEPARATOR)
        if (cut <= 0) return null
        return body.substring(0, cut) to body.substring(cut + SEPARATOR.length)
    }














    internal fun secretAccount(configId: String): String = "source.$configId"
    internal fun refreshSecretAccount(configId: String): String = "source-refresh.$configId"

    internal fun baseUrlOf(config: SourceConfig): String {
        if (!config.kind.needsServer) return config.baseUrl
        return PlatformSettings.getSecret(secretAccount(config.id)) ?: config.baseUrl
    }

    internal fun storeBaseUrl(config: SourceConfig, url: String) {
        if (!config.kind.needsServer) return
        PlatformSettings.putSecret(secretAccount(config.id), url.ifBlank { null })
    }













    fun duplicateOf(url: String, exceptId: String? = null): SourceConfig? {
        init()
        val wanted = canonicalUrl(url)
        if (wanted.isEmpty()) return null
        return _configs.value.firstOrNull {
            it.id != exceptId && canonicalUrl(baseUrlOf(it)) == wanted
        }
    }










    internal fun canonicalUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return trimmed
        val afterScheme = trimmed.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast(':').lowercase()
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        val path = afterScheme.substringAfter('/', "").trimEnd('/')
        val query = afterScheme.substringAfter('?', "").takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val defaultPort = if (scheme == "https") 443 else 80
        val portPart = if (port != null && port != defaultPort) ":$port" else ""
        return "$scheme://$host$portPart/$path$query".trimEnd('/').let {
            if (path.isEmpty() && query.isEmpty()) "$scheme://$host$portPart" else it
        }
    }








    internal fun normalizeAddonBase(raw: String): String = AddonClient.normalizeBase(raw)

    private val BUILT_IN_KINDS = listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE)

    private const val KEY_SOURCES = "sources"

    private const val KEY_JIOSAAVN_OPT_IN_V1 = "jiosaavn_opt_in_v1"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}










@Serializable
data class SourceConfig(
    val id: String = newSourceId(),
    val kind: SourceKind,

    val label: String = "",
    val baseUrl: String = "",

    val enabled: Boolean = kind != SourceKind.JIOSAAVN,
    val refreshEnabled: Boolean = false,
    val refreshUrl: String = "",
    @Transient val refreshUrlUnavailable: Boolean = false,
) {

    val displayName: String
        get() = label.ifBlank {
            SourceRegistry.baseUrlOf(this).takeIf { it.isNotBlank() }
                ?.let { hostOf(it) }
                ?: kind.label
        }








    val isComplete: Boolean
        get() = !kind.needsServer || (SourceRegistry.baseUrlOf(this).isNotBlank() &&
            (!refreshEnabled || refreshUrl.isNotBlank()))
}

private fun hostOf(url: String): String? = runCatching {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
    afterScheme.substringBefore('/').substringBefore('?').substringAfterLast(':').ifEmpty { null }
}.getOrNull()

private fun newSourceId(): String {
    val alphabet = "0123456789abcdef"
    return buildString(32) { repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) } }
}
