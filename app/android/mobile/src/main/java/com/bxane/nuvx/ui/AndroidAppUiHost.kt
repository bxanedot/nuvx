package com.bxane.nuvx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.AppleCatalogue
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.canvas.AppleArtistArtRepository
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.canvas.CanvasRepository
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.download.Downloads

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    @Composable
    override fun downloadedIds(): Set<String> {
        val saved by Downloads.saved.collectAsStateWithLifecycle()
        return saved.keys
    }

    override suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value || album.isBlank() || artist.isBlank()) return null
        return CanvasRepository.canvasForAlbum(album, artist)
    }

    /**
     * The clip the artist page plays, off the same proven Apple lookup — so
     * opening the artist from the card costs nothing more, and a card never
     * moves with somebody else's face. The YouTube page it is proved against is
     * fetched only when that lookup hasn't settled already.
     */
    override suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value) return null
        val art = AppleArtistArtRepository.artFor(browseId, artistName) {
            YtMusicRepository.artistLanding(browseId).getOrNull()?.let(AppleCatalogue::titlesOf).orEmpty()
        }
        return art?.videoUrl?.let { CanvasArtwork(url = it) }
    }
}
