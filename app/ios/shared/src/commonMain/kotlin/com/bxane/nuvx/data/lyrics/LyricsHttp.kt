package com.bxane.nuvx.data.lyrics

import com.bxane.nuvx.data.http.Http
import kotlinx.serialization.json.Json

private const val LYRICS_TIMEOUT_MS = 6_000L

internal const val LYRICS_AGENT = "nuvx (https://github.com/bxanedot/nuvx)"

internal val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }
















internal sealed interface LyricsAttempt {

    data class Answered(val body: String) : LyricsAttempt


    data object Unreachable : LyricsAttempt
}








internal suspend fun lyricsAttempt(
    url: String,
    query: Map<String, String> = emptyMap(),
    agent: String = LYRICS_AGENT,
    headers: Map<String, String> = emptyMap(),
    timeoutMillis: Long = LYRICS_TIMEOUT_MS,
): LyricsAttempt = try {
    LyricsAttempt.Answered(
        Http.getText(
            url,
            headers = mapOf(
                "User-Agent" to agent,
                "Accept" to "application/json",
            ) + headers,
            query = query,
            timeoutMillis = timeoutMillis,
        ),
    )
} catch (e: Throwable) {


    if (e is kotlinx.coroutines.CancellationException) throw e
    LyricsAttempt.Unreachable
}

internal suspend fun lyricsGet(
    url: String,
    query: Map<String, String> = emptyMap(),




    agent: String = LYRICS_AGENT,

    headers: Map<String, String> = emptyMap(),

    timeoutMillis: Long = LYRICS_TIMEOUT_MS,
): String? = runCatching {
    Http.getText(
        url,
        headers = mapOf(
            "User-Agent" to agent,
            "Accept" to "application/json",
        ) + headers,
        query = query,
        timeoutMillis = timeoutMillis,
    )
}.getOrNull()

internal suspend fun lyricsGetAuthorized(
    url: String,
    bearer: String,
    query: Map<String, String> = emptyMap(),
): String? = runCatching {
    Http.getText(
        url,
        headers = mapOf(
            "User-Agent" to LYRICS_AGENT,
            "Accept" to "application/json",
            "Authorization" to "Bearer $bearer",
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/",
        ),
        query = query,
        timeoutMillis = LYRICS_TIMEOUT_MS,
    )
}.getOrNull()









internal suspend fun lyricsGetBearer(
    url: String,
    bearer: String,
    query: Map<String, String> = emptyMap(),
): String? = runCatching {
    Http.getText(
        url,
        headers = mapOf(
            "User-Agent" to LYRICS_AGENT,
            "Accept" to "application/json",
            "Authorization" to "Bearer $bearer",
        ),
        query = query,
        timeoutMillis = LYRICS_TIMEOUT_MS,
    )
}.getOrNull()
