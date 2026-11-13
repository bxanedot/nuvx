

package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.UserPlaylist
import kotlinx.serialization.json.JsonElement

internal object InnertubeLibraryStateParser {
    private val OWNER_ICONS = setOf("DELETE", "EDIT")
    private val NOT_EDITABLE = listOf("LM", "SE", "RD", "OLAK", "MPRE")

    fun parseLibraryState(root: JsonElement): LibraryState? {
        val buttons = InnertubeParser.collectRenderers(root, "musicResponsiveHeaderRenderer")
            .firstOrNull()
            .a("buttons")
            .orEmpty()
        val save = buttons.firstNotNullOfOrNull { it.o("toggleButtonRenderer")?.takeIf { b -> b.isSaveToggle } }
            ?: return null
        if (save.s("isDisabled") == "true") return null
        val play = buttons.firstNotNullOfOrNull { it.o("musicPlayButtonRenderer").o("playNavigationEndpoint") }
        return LibraryState(
            playlistId = play.o("watchPlaylistEndpoint").s("playlistId")
                ?: play.o("watchEndpoint").s("playlistId")
                ?: return null,
            saved = save.s("isToggled") == "true",
        )
    }

    private val JsonElement?.isSaveToggle: Boolean
        get() = o("defaultIcon").s("iconType") == "BOOKMARK_BORDER" ||
            o("toggledIcon").s("iconType") == "BOOKMARK"

    fun parsePlaylistOwned(root: JsonElement): Boolean? {
        if (InnertubeParser.collectRenderers(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty()) {
            return true
        }
        val header = InnertubeParser.collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: return null
        if (InnertubeParser.collectRenderers(header, "menuNavigationItemRenderer")
                .any { it.o("icon").s("iconType") in OWNER_ICONS }
        ) {
            return true
        }
        return header.a("buttons").orEmpty().none { it.o("toggleButtonRenderer").isSaveToggle }
    }

    fun parseUserPlaylists(root: JsonElement): List<UserPlaylist> =
        parseUserPlaylists(InnertubeParser.parseLibraryItems(root))

    fun parseUserPlaylists(items: List<ShelfItem>): List<UserPlaylist> =
        items.mapNotNull { item ->
            val browseId = item.browseId ?: return@mapNotNull null
            if (!browseId.startsWith("VL")) return@mapNotNull null
            if (NOT_EDITABLE.any { browseId.startsWith("VL$it") }) return@mapNotNull null
            UserPlaylist(
                playlistId = browseId.removePrefix("VL"),
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
        }
}
