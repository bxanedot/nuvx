package com.bxane.nuvx.data.canvas

import com.bxane.nuvx.data.Http
import okhttp3.Request

internal fun canvasGet(url: String, headers: Map<String, String> = emptyMap()): String? {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return runCatching {
        Http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}

internal fun canvasGetWithStatus(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return runCatching {
        Http.client.newCall(request).execute().use { response ->
            response.code to if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrDefault(-1 to null)
}

internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"
