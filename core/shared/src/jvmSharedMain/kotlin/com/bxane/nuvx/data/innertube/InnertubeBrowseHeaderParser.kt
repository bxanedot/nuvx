package com.bxane.nuvx.data.innertube

import kotlinx.serialization.json.JsonElement

internal object InnertubeBrowseHeaderParser {
    fun parseBrowseHeader(root: JsonElement): InnertubeParser.BrowseHeader? {
        val header = InnertubeParser.HEADER_RENDERERS.firstNotNullOfOrNull {
            InnertubeParser.collectRenderers(root, it).firstOrNull()
        } ?: return null
        val title = header.o("title").runs()
        if (title.isBlank()) return null
        val subtitle = InnertubeParser.HEADER_CREDIT_LINES
            .map { header.o(it).runs() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" • ")
        return InnertubeParser.BrowseHeader(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = InnertubeParser.collectRenderers(header, "musicThumbnailRenderer").firstOrNull()
                .o("thumbnail").a("thumbnails").best(),
        )
    }

    fun parseAlbumPlaylistId(root: JsonElement): String? {
        val header = InnertubeParser.HEADER_RENDERERS.firstNotNullOfOrNull {
            InnertubeParser.collectRenderers(root, it).firstOrNull()
        }
        if (header != null) {
            InnertubeParser.collectRenderers(header, "musicPlayButtonRenderer")
                .firstNotNullOfOrNull { it.playlistIdFromPlayAction() }
                ?.let { return it }
            InnertubeParser.collectRenderers(header, "buttonRenderer")
                .firstNotNullOfOrNull { it.playlistIdFromPlayAction() }
                ?.let { return it }
        }

        val canonical = root.o("microformat").o("microformatDataRenderer").s("urlCanonical")
            ?: return null
        return canonical.substringAfter("list=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
    }

    private fun JsonElement.playlistIdFromPlayAction(): String? {
        val endpoint = o("playNavigationEndpoint")
            ?: o("navigationEndpoint")
            ?: this
        return endpoint.o("watchPlaylistEndpoint").s("playlistId")
            ?: endpoint.o("watchEndpoint").s("playlistId")
    }

    fun parseDescription(root: JsonElement): String? {
        val shelf = InnertubeParser.collectRenderers(root, "musicDescriptionShelfRenderer")
            .firstOrNull()?.o("description").runs()
        if (shelf.isNotBlank()) return shelf
        val onHeader = (InnertubeParser.HEADER_RENDERERS + "musicImmersiveHeaderRenderer")
            .firstNotNullOfOrNull { name ->
                InnertubeParser.collectRenderers(root, name).firstOrNull()
                    ?.o("description").runs().takeIf { it.isNotBlank() }
            }
        return onHeader
    }
}
