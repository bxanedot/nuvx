package com.bxane.nuvx.core

import io.ktor.client.HttpClient

/**
 * Lyrics sources in the order they're asked. Add a provider only after confirming its public API and terms allow
 * third-party apps; record that in docs/LYRICS_PROVIDERS.md.
 */
fun defaultLyricsProviders(http: HttpClient): List<LyricsProvider> = listOf(
    LrcLib(http),
)
