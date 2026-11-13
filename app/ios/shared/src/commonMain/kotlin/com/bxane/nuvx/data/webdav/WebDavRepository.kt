package com.bxane.nuvx.data.webdav

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.remote.RemoteArtwork
import com.bxane.nuvx.data.remote.RemoteSong
import com.bxane.nuvx.data.remote.WebDavClient
import com.bxane.nuvx.data.remote.WebDavConfig
import com.bxane.nuvx.data.remote.WebDavFailure
import com.bxane.nuvx.data.settings.AppSettings



















object WebDavRepository {


    fun isConfigured(): Boolean = WebDavConfig.isConfigured(AppSettings.webDavUrl.value)























    suspend fun getSongs(): List<Song> {
        val url = AppSettings.webDavUrl.value
        if (!WebDavConfig.isConfigured(url)) return emptyList()
        val listing = WebDavClient.listLibrary(
            baseUrl = url,
            username = AppSettings.webDavUsername.value,
            password = AppSettings.webDavPassword.value,
        ).getOrElse { throw it }


        val artByDir = listing.images.groupBy { WebDavConfig.dirKeyOf(it.url) }
        return listing.audio.map { entry ->
            val siblings = artByDir[WebDavConfig.dirKeyOf(entry.url)].orEmpty()
            entry.toSong(RemoteArtwork.pick(siblings.map { it.url }))
        }
    }








    suspend fun testConnection(
        url: String,
        username: String,
        password: String,
    ): WebDavFailure? = WebDavClient.testConnection(url, username, password)
















    fun WebDavClient.Entry.toSong(artworkUrl: String? = null): Song {
        val album = WebDavConfig.folderNameOf(url)
        val fromUrl = RemoteSong.build(
            videoId = WebDavConfig.idFor(url),
            streamUrl = url,
            fileName = WebDavConfig.fileNameOf(url),
            albumName = album,
        )
        val stem = displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
        val (artist, title) = if (stem == null) null to null else RemoteSong.splitArtistTitle(stem)
        return fromUrl.copy(
            title = title ?: fromUrl.title,
            artist = artist ?: fromUrl.artist,
            thumbnailUrl = artworkUrl,
        )
    }
}
