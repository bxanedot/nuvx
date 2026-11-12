package com.bxane.nuvx.ui

import android.content.Context
import android.widget.Toast
import com.bxane.nuvx.R
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainBrowseOpener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val openDetail: (String, String, String, String?, BrowseType) -> Unit,
) {
    private var lookupGeneration = 0L

    fun openByName(
        browseId: String?,
        name: String,
        subtitle: String?,
        type: BrowseType,
        artwork: String? = null,
    ) {
        val generation = ++lookupGeneration
        if (browseId != null) {
            val credit = subtitle ?: context.getString(
                if (type == BrowseType.ARTIST) R.string.artist else R.string.album,
            )
            openDetail(browseId, name, credit, artwork, type)
            return
        }
        scope.launch {
            val filter = if (type == BrowseType.ARTIST) {
                SearchFilter.ARTISTS
            } else {
                SearchFilter.ALBUMS
            }
            val query = listOfNotNull(name, subtitle).joinToString(" ")
            val hit = YtMusicRepository.search(query, filter).getOrNull()
                ?.filterIsInstance<SearchResult.Browse>()
                ?.firstOrNull()
                ?.item
            if (generation != lookupGeneration) return@launch
            if (hit == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldnt_find, name),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                openDetail(
                    hit.browseId,
                    hit.title,
                    hit.subtitle,
                    hit.thumbnailUrl ?: artwork,
                    hit.type,
                )
            }
        }
    }
}
