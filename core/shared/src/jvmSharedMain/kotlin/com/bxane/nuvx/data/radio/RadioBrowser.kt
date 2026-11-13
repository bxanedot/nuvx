
package com.bxane.nuvx.data.radio

import com.bxane.nuvx.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

@Serializable
data class RadioCountry(
    val name: String,
    @SerialName("iso_3166_1") val code: String,
    @SerialName("stationcount") val stationCount: Int = 0,
) {
    val flag: String
        get() = code.uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("").orEmpty()
}

@Serializable
data class RadioStation(
    @SerialName("stationuuid") val id: String,
    val name: String,
    @SerialName("url_resolved") val streamUrl: String,
    val favicon: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
) {
    val subtitle: String
        get() = listOfNotNull(
            tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
                .joinToString(" · ") { t -> t.replaceFirstChar { it.uppercase() } }.ifEmpty { null },
            listOfNotNull(codec.ifBlank { null }, bitrate.takeIf { it > 0 }?.let { "$it kbps" })
                .joinToString(" ").ifEmpty { null },
        ).joinToString(" · ")
}

object RadioBrowser {
    private val hosts = listOf("all.api.radio-browser.info", "de1.api.radio-browser.info", "fi1.api.radio-browser.info")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private const val AGENT = "nuvx/1.0"

    suspend fun countries(): List<RadioCountry> =
        get("countries", "order" to "stationcount", "reverse" to "true", "hidebroken" to "true")
            .let { json.decodeFromString<List<RadioCountry>>(it) }
            .filter { it.stationCount > 0 && it.code.length == 2 }

    suspend fun topStations(countryCode: String, limit: Int = 150): List<RadioStation> =
        stations("stations/bycountrycodeexact/${countryCode.uppercase()}", limit)

    suspend fun search(query: String, limit: Int = 100): List<RadioStation> =
        stations("stations/search", limit, "name" to query)

    private suspend fun stations(path: String, limit: Int, vararg extra: Pair<String, String>): List<RadioStation> =
        get(path, "hidebroken" to "true", "order" to "clickcount", "reverse" to "true", "limit" to "$limit", *extra)
            .let { json.decodeFromString<List<RadioStation>>(it) }
            .filter { it.streamUrl.startsWith("http") }
            .distinctBy { it.streamUrl }

    suspend fun countClick(stationId: String) {
        runCatching { get("url/$stationId") }
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in hosts) {
            try {
                return@withContext fetch(url(host, path, query))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Radio Browser unreachable")
    }

    private fun url(host: String, path: String, query: Array<out Pair<String, String>>): HttpUrl =
        "https://$host/json/$path".toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private fun fetch(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Radio Browser answered ${response.code}" }
            return response.body.string()
        }
    }
}
