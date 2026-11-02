package com.bxane.nuvx.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun nuvxHttpClient() = HttpClient(Darwin) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
    install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
}
