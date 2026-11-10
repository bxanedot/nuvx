package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.model.Song
import java.util.Locale

data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    val headers: Map<String, String> = emptyMap(),
    val belowRequest: Boolean = false,
    val durationSec: Int? = null,
    val sourceConfigId: String? = null,
)

sealed interface StreamRequest {
    data object Lossless : StreamRequest

    data class Capped(val maxKbps: Int) : StreamRequest

    data object Best : StreamRequest
}

sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth
    data class Unreachable(val reason: String) : SourceHealth
    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}

interface MusicSource {

    val configId: String

    val kind: SourceKind

    val displayName: String

    suspend fun health(): SourceHealth

    suspend fun search(
        query: String,
        limit: Int = 25,
        waitForAll: Boolean = false,
        request: StreamRequest? = null,
    ): List<Song>

    suspend fun stream(trackId: String, request: StreamRequest): SourceStream?
}
