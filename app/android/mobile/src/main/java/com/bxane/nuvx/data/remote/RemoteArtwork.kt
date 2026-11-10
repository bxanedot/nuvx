package com.bxane.nuvx.data.remote

import java.net.URLDecoder
import java.util.Locale

object RemoteArtwork {

    fun pick(siblingUrls: List<String>): String? {
        if (siblingUrls.isEmpty()) return null
        return siblingUrls.sortedWith(
            compareBy(
                { url ->
                    preferredStems.indexOfFirst { stem(url).startsWith(it) }
                        .takeIf { it >= 0 } ?: Int.MAX_VALUE
                },
                { stem(it) },
            ),
        ).first()
    }

    private val preferredStems = listOf("cover", "folder", "front", "albumart", "album", "artwork")

    private fun stem(fileUrl: String): String = runCatching {
        URLDecoder.decode(fileUrl.trimEnd('/').substringAfterLast('/'), "UTF-8")
    }.getOrDefault(fileUrl.trimEnd('/').substringAfterLast('/'))
        .substringBeforeLast('.')
        .lowercase(Locale.ROOT)
}
