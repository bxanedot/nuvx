package com.bxane.nuvx.data.canvas

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object CanvasRepository {

    private const val TAG = "CanvasRepository"
    private const val CACHE_SIZE = 64

    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val lock = Mutex()

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null

        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null

        val album = song.albumName
        val spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value
        val key = cacheKey("song|${song.videoId}", spotifyFirst)

        return resolve(key, album != null) {
            if (spotifyFirst) {
                firstHit(
                    { CommunityCanvas.search(song.videoId, song.title, song.artist) },
                    { SpotifyCanvas.search(title, artist, album) },
                    { AppleMusicCanvas.search(title, artist, album) },
                    { TidalCanvas.search(title, artist, album) },
                ) { it.matches(title, artist, album) }
            } else {
                firstHit(
                    { CommunityCanvas.search(song.videoId, song.title, song.artist) },
                    { AppleMusicCanvas.search(title, artist, album) },
                    { TidalCanvas.search(title, artist, album) },
                    { SpotifyCanvas.search(title, artist, album) },
                ) { it.matches(title, artist, album) }
            }
        }
    }

    fun cached(song: Song): CanvasArtwork? {
        val key = cacheKey(
            base = "song|${song.videoId}",
            spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value,
        )
        return synchronized(cache) { cache[key]?.artwork }
    }

    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? {
        val name = album.cleaned()
        val credit = artist.cleaned()
        if (name.isBlank() || credit.isBlank()) return null

        val spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value
        return resolve(cacheKey("album|$name|$credit", spotifyFirst), withAlbum = true) {
            if (spotifyFirst) {
                firstHit(
                    { SpotifyCanvas.searchAlbum(name, credit) },
                    { AppleMusicCanvas.searchAlbum(name, credit) },
                    { TidalCanvas.searchAlbum(name, credit) },
                ) { it.matches(name, credit, name) }
            } else {
                firstHit(
                    { AppleMusicCanvas.searchAlbum(name, credit) },
                    { TidalCanvas.searchAlbum(name, credit) },
                    { SpotifyCanvas.searchAlbum(name, credit) },
                ) { it.matches(name, credit, name) }
            }
        }
    }

    private fun cacheKey(base: String, spotifyFirst: Boolean): String =
        "$base|spotifyFirst=$spotifyFirst"

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? = lock.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    private fun Entry.reusable(withAlbum: Boolean): Boolean =
        artwork != null || this.withAlbum || !withAlbum

    private suspend fun firstHit(
        vararg sources: suspend () -> CanvasArtwork?,
        accept: (CanvasArtwork) -> Boolean,
    ): CanvasArtwork? {
        for (source in sources) {
            val found = runCatching { source() }
                .onFailure { Log.d(TAG, "source failed: ${it.message}") }
                .getOrNull()
                ?: continue
            if (!accept(found)) {
                Log.d(TAG, "rejected '${found.title}' by '${found.artist}'")
                continue
            }
            return found
        }
        return null
    }

    private fun String.cleaned(): String = replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
