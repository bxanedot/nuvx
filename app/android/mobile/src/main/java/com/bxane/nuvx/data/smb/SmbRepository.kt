package com.bxane.nuvx.data.smb

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.remote.RemoteArtwork
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmbRepository {

    fun isConfigured(): Boolean =
        SmbConfig.isConfigured(AppSettings.smbHost.value, AppSettings.smbShare.value)

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val host = SmbAuth.host
        val share = SmbAuth.share
        val listing = SmbClient.listLibrary().getOrThrow()
        val artByDir = listing.images.groupBy { dirKey(it.path) }
        listing.audio.map { entry ->
            toSong(host, share, entry, artByDir[dirKey(entry.path)].orEmpty())
        }
    }

    suspend fun testConnection(
        host: String,
        share: String,
        basePath: String,
        username: String,
        password: String,
    ): Result<Unit> = SmbClient.testConnection(host, share, basePath, username, password)

    private fun toSong(
        host: String,
        share: String,
        entry: SmbClient.Entry,
        siblings: List<SmbClient.Entry>,
    ): Song {
        val fullPath = entry.path
        val album = fullPath.substringBeforeLast('/', "").substringAfterLast('/').trim()
            .takeIf { it.isNotBlank() }
        val coverUrls = siblings.map { streamUrl(host, share, it.path) }
        return SmbConfig.songFor(host, share, fullPath, albumName = album).copy(
            thumbnailUrl = RemoteArtwork.pick(coverUrls),
        )
    }

    private fun streamUrl(host: String, share: String, path: String): String =
        "smb://${SmbConfig.normalizeHost(host)}/${share.trim().trim('/')}/${path.trim('/')}"

    private fun dirKey(path: String): String =
        path.trim('/').substringBeforeLast('/', "").lowercase(java.util.Locale.ROOT)
}
