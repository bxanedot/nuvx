package com.bxane.nuvx.data.remote

import com.bxane.nuvx.data.model.Song








object RemoteSong {









    fun splitArtistTitle(base: String): Pair<String?, String?> {
        val separator = base.indexOf(" - ")
        if (separator < 0) return null to base
        val artist = base.substring(0, separator).trim().takeIf { it.isNotEmpty() }
        val title = base.substring(separator + 3).trim().takeIf { it.isNotEmpty() }
        return artist to title
    }


















    fun build(
        videoId: String,
        streamUrl: String,
        fileName: String,
        albumName: String?,
    ): Song {
        val base = fileName.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: fileName
        val (artist, title) = splitArtistTitle(base)
        return Song(
            videoId = videoId,
            title = title ?: base,
            artist = artist ?: WebDavConfig.UNKNOWN_ARTIST,


            thumbnailUrl = null,
            durationText = null,



            albumName = albumName?.trim()?.takeIf { it.isNotEmpty() },



            localPath = streamUrl,
        )
    }
}
