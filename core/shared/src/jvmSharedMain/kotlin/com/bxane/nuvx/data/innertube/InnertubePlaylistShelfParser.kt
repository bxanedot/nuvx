

package com.bxane.nuvx.data.innertube

import kotlinx.serialization.json.JsonElement

internal object InnertubePlaylistShelfParser {
    fun parsePlaylistShelf(root: JsonElement): InnertubeParser.PlaylistShelfPage? {
        val scope: JsonElement = root.o("continuationContents")
            ?: root.o("contents").o("twoColumnBrowseResultsRenderer").o("secondaryContents")
            ?: return null
        val playlistScope = scope.o("musicPlaylistShelfContinuation")
            ?: InnertubeParser.collectRenderers(scope, "musicPlaylistShelfRenderer").firstOrNull()
        val suggestionShelves = InnertubeParser.collectRenderers(scope, "musicShelfRenderer")
            .filter { it.o("title").runs() == "Suggestions" }
        if (playlistScope == null && suggestionShelves.isEmpty()) return null

        val pageCredit = InnertubeParser.pageCredit(root)
        val songs = playlistScope?.let { playlist ->
            InnertubeParser.collectRenderers(playlist, "musicResponsiveListItemRenderer")
                .mapNotNull { InnertubeParser.parseResponsiveListItem(it, pageCredit) }
                .distinctBy { it.videoId }
        }.orEmpty()
        val knownSongs = songs.mapTo(HashSet()) { it.videoId }
        val suggested = suggestionShelves
            .flatMap { InnertubeParser.collectRenderers(it, "musicResponsiveListItemRenderer") }
            .mapNotNull { InnertubeParser.parseResponsiveListItem(it, pageCredit) }
            .filterNot { it.videoId in knownSongs }
            .distinctBy { it.videoId }
        val token = playlistScope?.let { InnertubeParser.continuationToken(it) }
        return InnertubeParser.PlaylistShelfPage(songs, suggested, token)
    }
}
