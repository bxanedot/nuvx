package com.bxane.nuvx.ui.player

import com.bxane.nuvx.data.model.ArtistCredit
import com.bxane.nuvx.data.model.ArtistNameIndex
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.CreditJoiner

fun artistCredits(
    artist: String,
    artists: List<ArtistRef>,
    primaryArtistId: String?,
): List<ArtistCredit> {
    val stated = artists.filter { it.name.isNotBlank() }.map { credit ->
        ArtistCredit(name = credit.name, browseId = credit.browseId)
    }
    val credits = stated.ifEmpty { splitCreditLine(artist, primaryArtistId) }
    ArtistNameIndex.record(stated.map { ArtistRef(it.name, it.browseId) })
    return credits
}

private fun splitCreditLine(artist: String, primaryArtistId: String?): List<ArtistCredit> {
    val text = artist.trim()
    if (text.isEmpty()) return emptyList()

    val cuts = CREDIT_SEPARATOR.findAll(text).toList()
    if (cuts.isEmpty()) {
        return listOf(ArtistCredit(name = text, browseId = primaryArtistId))
    }

    val out = mutableListOf<ArtistCredit>()
    var start = 0
    for (cut in cuts) {
        val name = text.substring(start, cut.range.first).trim()
        if (name.isNotEmpty()) {
            out += ArtistCredit(
                name = name,
                browseId = if (out.isEmpty()) primaryArtistId else null,
            )
        }
        start = cut.range.last + 1
    }
    val tail = text.substring(start).trim()
    if (tail.isNotEmpty()) out += ArtistCredit(name = tail, browseId = null)
    if (out.isEmpty()) out += ArtistCredit(name = text, browseId = primaryArtistId)
    return out
}

private val CREDIT_SEPARATOR = CreditJoiner