// SPDX-License-Identifier: GPL-3.0-or-later
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

/** A country with live stations on Radio Browser. */
@Serializable
data class RadioCountry(
    val name: String,
    @SerialName("iso_3166_1") val code: String,
    @SerialName("stationcount") val stationCount: Int = 0,
) {
    /** The country's flag, built from its two-letter code. */
    val flag: String
        get() = code.uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("").orEmpty()
}

/** One live station, as Radio Browser lists it. */
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val id: String,
    val name: String,
    /** The playable stream, playlists (.pls/.m3u) already followed. */
    @SerialName("url_resolved") val streamUrl: String,
    val favicon: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
) {
    /** "Pop · Rock · MP3 128 kbps" — the line under the name. */
    val subtitle: String
        get() = listOfNotNull(
            tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
                .joinToString(" · ") { t -> t.replaceFirstChar { it.uppercase() } }.ifEmpty { null },
            listOfNotNull(codec.ifBlank { null }, bitrate.takeIf { it > 0 }?.let { "$it kbps" })
                .joinToString(" ").ifEmpty { null },
        ).joinToString(" · ")
}

/**
 * Live radio from Radio Browser (radio-browser.info), the free community
 * directory of internet stations: tens of thousands of them, from nearly every
 * country. Broken streams are filtered out server side.
 */
object RadioBrowser {
    // The round-robin name resolves to every mirror; de1 is the fallback when it doesn't answer.
    private val hosts = listOf("all.api.radio-browser.info", "de1.api.radio-browser.info", "fi1.api.radio-browser.info")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private const val AGENT = "nuvx/1.0"

    /** Every country with at least one station, most stations first. */
    suspend fun countries(): List<RadioCountry> =
        get("countries", "order" to "stationcount", "reverse" to "true", "hidebroken" to "true")
            .let { json.decodeFromString<List<RadioCountry>>(it) }
            .filter { it.stationCount > 0 && it.code.length == 2 }

    /** A country's most played stations. */
    suspend fun topStations(countryCode: String, limit: Int = 150): List<RadioStation> =
        stations("stations/bycountrycodeexact/${countryCode.uppercase()}", limit)

    /** Stations whose name matches [query], worldwide. */
    suspend fun search(query: String, limit: Int = 100): List<RadioStation> =
        stations("stations/search", limit, "name" to query)

    private suspend fun stations(path: String, limit: Int, vararg extra: Pair<String, String>): List<RadioStation> =
        get(path, "hidebroken" to "true", "order" to "clickcount", "reverse" to "true", "limit" to "$limit", *extra)
            .let { json.decodeFromString<List<RadioStation>>(it) }
            .filter { it.streamUrl.startsWith("http") }
            .distinctBy { it.streamUrl }

    /**
     * Tells Radio Browser a station was played, as its API asks clients to: it
     * is what ranks the top lists. Best effort; failures are ignored.
     */
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
