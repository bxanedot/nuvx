package com.bxane.nuvx.data.sources

object SourceTrackKeys {
    const val PREFIX = "src:"
    const val SEPARATOR = "::"

    fun parse(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val cut = body.indexOf(SEPARATOR)
        if (cut <= 0) return null
        return body.substring(0, cut) to body.substring(cut + SEPARATOR.length)
    }
}
