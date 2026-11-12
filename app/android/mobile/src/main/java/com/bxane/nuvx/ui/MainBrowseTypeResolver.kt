package com.bxane.nuvx.ui

import com.bxane.nuvx.data.LocalGroups
import com.bxane.nuvx.data.OfflinePlaylists
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.download.Downloads

internal object MainBrowseTypeResolver {
    fun resolve(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
        browseId.startsWith(Downloads.PLAYLIST_PREFIX) ->
            Downloads.recordIdOf(browseId)
                ?.let { Downloads.collections.value[it] }
                ?.takeIf { !it.playlist }
                ?.let { BrowseType.ALBUM }
                ?: BrowseType.PLAYLIST
        browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
        browseId.startsWith(OfflinePlaylists.PREFIX) -> BrowseType.PLAYLIST
        browseId.startsWith("UC") -> BrowseType.ARTIST
        browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
        browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
        else -> fallback
    }
}
