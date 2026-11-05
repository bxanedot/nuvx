package com.bxane.nuvx.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads YouTube Music InnerTube responses (WEB_REMIX) into nuvx models. Unknown shapes are skipped, never guessed. */
object YtmJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSearch(body: String, videoResults: Boolean = false): SearchResults {
        val root = json.parseToJsonElement(body)
        val tracks = mutableListOf<Track>()
        val collections = mutableListOf<Collection>()
        for (item in root.findAll("musicResponsiveListItemRenderer")) {
            val columns = item.arr("flexColumns").map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
            val title = columns.getOrNull(0).runs().firstOrNull()?.str("text") ?: continue
            val meta = columns.getOrNull(1).runs()
            val art = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail").bestThumb()
            val browse = item.obj("navigationEndpoint")?.obj("browseEndpoint")
            // Only the row's own play button counts; menu entries (artist radio, shuffle) also carry watchEndpoints.
            val videoId = item.obj("playlistItemData")?.str("videoId")
                ?: item.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
                    ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            if (browse == null && videoId != null) {
                tracks += Track(videoId, title, meta.artists(), meta.album(), meta.durationMs(), art,
                    artistId = meta.artistId(), albumId = meta.albumId(), mediaKind = if (videoResults) MediaKind.VIDEO else MediaKind.SONG)
            } else if (browse != null) {
                val kind = when (browse.pageType()) {
                    "MUSIC_PAGE_TYPE_ALBUM" -> Collection.Kind.ALBUM
                    "MUSIC_PAGE_TYPE_ARTIST" -> Collection.Kind.ARTIST
                    "MUSIC_PAGE_TYPE_PLAYLIST" -> Collection.Kind.PLAYLIST
                    else -> continue
                }
                val id = browse.str("browseId") ?: continue
                collections += Collection(kind, id, title, meta.joinToString("") { it.str("text").orEmpty() }.ifBlank { null }, art)
            }
        }
        // Songs inside the "top result" card show their artist once on the card, not on each row.
        val cardArtist = root.findAll("musicCardShelfRenderer").associate { card ->
            val sub = card.obj("subtitle").runs()
            val name = sub.artists().firstOrNull()
                ?: card.obj("title").runs().joinToString("") { it.str("text").orEmpty() }.takeIf { sub.firstOrNull()?.str("text") == "Artist" }
            val ids = card.arr("contents").mapNotNull { it.obj("musicResponsiveListItemRenderer")?.obj("playlistItemData")?.str("videoId") }
            ids to name
        }.flatMap { (ids, name) -> ids.map { it to name } }.toMap()
        val withArtists = tracks.map { t -> if (t.artists.isEmpty()) cardArtist[t.videoId]?.let { t.copy(artists = listOf(it)) } ?: t else t }
        return SearchResults(withArtists.distinctBy { it.videoId }, collections.distinctBy { it.browseId })
    }

    /**
     * Album or playlist page. Album rows carry no artwork or (usually) artist of their own, so those come from the header;
     * playlist rows have their own artist, album and artwork, which win.
     * shortcut: first page only (about 70 rows on long playlists); follow `continuations` when someone needs the rest.
     */
    fun parseAlbum(browseId: String, body: String): Album {
        val root = json.parseToJsonElement(body)
        val header = root.findAll("musicResponsiveHeaderRenderer").firstOrNull()
        val title = header?.obj("title").runs().joinToString("") { it.str("text").orEmpty() }.ifBlank { "Album" }
        val artist = header?.obj("straplineTextOne").runs().joinToString("") { it.str("text").orEmpty() }.ifBlank { null }
        val subtitle = header?.obj("subtitle").runs().mapNotNull { it.str("text") }.orEmpty()
        val year = subtitle.lastOrNull { y -> y.length == 4 && y.all(Char::isDigit) }
        val isPlaylist = subtitle.firstOrNull()?.trim().equals("Playlist", ignoreCase = true)
        val art = header?.findThumb()
        val tracks = root.findAll("musicResponsiveListItemRenderer").mapNotNull { row ->
            val id = row.obj("playlistItemData")?.str("videoId") ?: return@mapNotNull null
            val cols = row.arr("flexColumns").map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
            val name = cols.getOrNull(0).runs().firstOrNull()?.str("text") ?: return@mapNotNull null
            val rowArtists = cols.drop(1).flatMap { it.runs() }.artists()
            val rowAlbum = cols.drop(1).flatMap { it.runs() }.album()
            val rowArt = row.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail").bestThumb()
            val length = row.arr("fixedColumns").firstNotNullOfOrNull { it.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text").runs().firstOrNull()?.str("text")?.let(::parseClock) }
            val rowRuns = cols.drop(1).flatMap { it.runs() }
            Track(id, name, rowArtists.ifEmpty { listOfNotNull(artist) }, rowAlbum ?: title.takeUnless { isPlaylist }, length, rowArt ?: art,
                artistId = rowRuns.artistId() ?: header?.obj("straplineTextOne").runs().artistId(),
                albumId = rowRuns.albumId() ?: browseId.takeUnless { isPlaylist })
        }
        return Album(browseId, title, artist, year, art, tracks.distinctBy { it.videoId })
    }

    private fun JsonElement.findThumb(): String? =
        findAll("musicThumbnailRenderer").firstNotNullOfOrNull { it.obj("thumbnail").bestThumb() }

    /** Artist page: browseable music shelves and video shelves returned by the catalog. */
    fun parseArtist(browseId: String, body: String): Artist {
        val root = json.parseToJsonElement(body)
        val header = (root.findAll("musicImmersiveHeaderRenderer") + root.findAll("musicVisualHeaderRenderer")).firstOrNull()
        val name = header?.obj("title").runs().joinToString("") { it.str("text").orEmpty() }.ifBlank { "Artist" }
        val art = header?.findThumb()
        val top = root.findAll("musicShelfRenderer").firstOrNull()?.let { parseSearch(it.toString()).tracks }.orEmpty()
        val shelves = root.findAll("musicCarouselShelfRenderer").mapNotNull { shelf ->
            val title = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title").runs()
                .joinToString("") { it.str("text").orEmpty() }
            val items = shelf.arr("contents").mapNotNull { it.obj("musicTwoRowItemRenderer") }.mapNotNull { card ->
                val be = card.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return@mapNotNull null
                val kind = when (be.pageType()) {
                    "MUSIC_PAGE_TYPE_ALBUM" -> Collection.Kind.ALBUM
                    "MUSIC_PAGE_TYPE_PLAYLIST" -> Collection.Kind.PLAYLIST
                    "MUSIC_PAGE_TYPE_ARTIST" -> Collection.Kind.ARTIST
                    else -> return@mapNotNull null
                }
                Collection(kind, be.str("browseId") ?: return@mapNotNull null,
                    card.obj("title").runs().joinToString("") { it.str("text").orEmpty() },
                    card.obj("subtitle").runs().joinToString("") { it.str("text").orEmpty() }.ifBlank { null },
                    card.obj("thumbnailRenderer")?.findThumb())
            }
            if (title.isBlank() || items.isEmpty()) null else ArtistShelf(title, items)
        }
        val videoShelves = root.findAll("musicCarouselShelfRenderer").mapNotNull { shelf ->
            val title = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title").runs()
                .joinToString("") { it.str("text").orEmpty() }
            val category = artistVideoCategory(title)
            val videos = artistVideoTracks(browseId, name, shelf.arr("contents"))
            if (title.isBlank() || videos.isEmpty()) null
            else {
                val continuation = shelf.continuation()
                val moreBrowse = shelf.findAll("moreContentButton").firstNotNullOfOrNull { it.findAll("browseEndpoint").firstOrNull() }
                ArtistVideoShelf(
                    title, category, videos, continuation,
                    moreBrowseId = moreBrowse?.str("browseId"),
                    moreBrowseParams = moreBrowse?.str("params"),
                    continuationBrowseId = browseId.takeIf { continuation != null },
                )
            }
        }
        return Artist(browseId, name, art, top, shelves, videoShelves)
    }

    fun parseArtistVideoPage(
        browseId: String,
        artistName: String,
        shelfTitle: String,
        body: String,
        pageBrowseId: String = browseId,
    ): ArtistVideoShelf {
        val root = json.parseToJsonElement(body)
        val page = root.findAll("musicCarouselShelfContinuation").firstOrNull()
        val carouselItems = page?.let { artistVideoTracks(browseId, artistName, it.arr("contents")) }.orEmpty()
        val playlistShelves = root.findAll("musicPlaylistShelfRenderer").map { shelf ->
            shelf to parseSearch(shelf.toString(), videoResults = true).tracks
        }
        val selectedPlaylistShelf = playlistShelves.firstOrNull { (_, items) -> items.isNotEmpty() }
        val listItems = (selectedPlaylistShelf?.second ?: if (playlistShelves.isEmpty()) {
            parseSearch(root.toString(), videoResults = true).tracks
        } else {
            emptyList()
        }).map { track ->
            track.copy(artists = track.artists.ifEmpty { listOf(artistName) }, artistId = track.artistId ?: browseId)
        }
        val items = (carouselItems + listItems).distinctBy { it.videoId }
        val continuation = page?.continuation()
            ?: selectedPlaylistShelf?.first?.continuation()
            ?: if (playlistShelves.isEmpty()) root.findAll("musicShelfRenderer").firstOrNull()?.continuation() else null
        return ArtistVideoShelf(
            shelfTitle, artistVideoCategory(shelfTitle), items, continuation,
            continuationBrowseId = pageBrowseId.takeIf { continuation != null },
        )
    }

    private fun artistVideoTracks(browseId: String, artistName: String, contents: List<JsonElement>) =
        contents.mapNotNull { it.obj("musicTwoRowItemRenderer") }.mapNotNull { card ->
            val endpoint = card.obj("navigationEndpoint")?.obj("watchEndpoint") ?: return@mapNotNull null
            val id = endpoint.str("videoId")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return@mapNotNull null
            val title = card.obj("title").runs().joinToString("") { it.str("text").orEmpty() }.ifBlank { return@mapNotNull null }
            val duration = card.findAll("musicThumbnailOverlayTimeStatusRenderer").firstOrNull()
                ?.obj("text").runs().joinToString("") { it.str("text").orEmpty() }.let(::parseClock)
            Track(id, title, listOf(artistName), durationMs = duration,
                artworkUrl = card.obj("thumbnailRenderer")?.findThumb(), artistId = browseId, mediaKind = MediaKind.VIDEO)
        }.distinctBy { it.videoId }

    private fun artistVideoCategory(title: String): ArtistVideoCategory {
        val words = " ${title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()} "
        return when {
            " behind the scenes " in words -> ArtistVideoCategory.BEHIND_THE_SCENES
            " interview " in words -> ArtistVideoCategory.INTERVIEW
            " acoustic " in words -> ArtistVideoCategory.ACOUSTIC
            " lyric video " in words || " lyrics video " in words -> ArtistVideoCategory.LYRIC_VIDEO
            " visualizer " in words || " visualiser " in words -> ArtistVideoCategory.VISUALIZER
            " live " in words || " performance " in words || " concert " in words -> ArtistVideoCategory.LIVE_PERFORMANCE
            " music video " in words || " music videos " in words -> ArtistVideoCategory.MUSIC_VIDEO
            else -> ArtistVideoCategory.OTHER
        }
    }

    fun parseRadio(body: String): RadioPage {
        val root = json.parseToJsonElement(body)
        val tracks = root.findAll("playlistPanelVideoRenderer").mapNotNull { r ->
            val id = r.str("videoId") ?: return@mapNotNull null
            val byline = r.obj("longBylineText").runs()
            Track(
                videoId = id,
                title = r.obj("title").runs().firstOrNull()?.str("text") ?: return@mapNotNull null,
                artists = byline.artists(),
                album = byline.album(),
                artistId = byline.artistId(),
                albumId = byline.albumId(),
                durationMs = r.obj("lengthText").runs().firstOrNull()?.str("text")?.let(::parseClock),
                artworkUrl = r.obj("thumbnail").bestThumb(),
            )
        }
        val continuation = (root.findAll("nextRadioContinuationData") + root.findAll("nextContinuationData"))
            .firstNotNullOfOrNull { it.str("continuation") }
        return RadioPage(tracks.distinctBy { it.videoId }, continuation)
    }

    /** "3:45" or "1:02:03" to milliseconds; null for anything else. */
    fun parseClock(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
    }

    private fun List<JsonObject>.browseIdOf(pages: Set<String>) =
        firstNotNullOfOrNull { r -> r.obj("navigationEndpoint")?.obj("browseEndpoint")?.takeIf { it.pageType() in pages }?.str("browseId") }
    private fun List<JsonObject>.artistId() = browseIdOf(ARTIST_PAGES)
    private fun List<JsonObject>.albumId() = browseIdOf(setOf("MUSIC_PAGE_TYPE_ALBUM"))

    private fun List<JsonObject>.artists() =
        filter { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.pageType() in ARTIST_PAGES }.mapNotNull { it.str("text") }

    private fun List<JsonObject>.album() =
        firstOrNull { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.pageType() == "MUSIC_PAGE_TYPE_ALBUM" }?.str("text")

    private fun List<JsonObject>.durationMs() = firstNotNullOfOrNull { it.str("text")?.let(::parseClock) }

    private val ARTIST_PAGES = setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")

    private fun JsonObject.pageType() = obj("browseEndpointContextSupportedConfigs")
        ?.obj("browseEndpointContextMusicConfig")?.str("pageType")

    private fun JsonObject?.runs(): List<JsonObject> = this?.arr("runs")?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject?.bestThumb(): String? = this?.arr("thumbnails")?.lastOrNull()?.let { it as? JsonObject }?.str("url")

    private fun JsonElement.obj(key: String) = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()
    private fun JsonElement.str(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.continuation(): String? = (
        findAll("nextContinuationData").firstNotNullOfOrNull { it.str("continuation") }
            ?: findAll("continuationItemRenderer").firstNotNullOfOrNull {
                it.obj("continuationEndpoint")?.obj("continuationCommand")?.str("token")
            }
        )?.takeIf(String::isNotBlank)

    private fun JsonElement.findAll(key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k == key && v is JsonObject) out += v else walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return out
    }
}

/**
 * The same artwork at about [size] px instead of the small list thumbnail: Google-hosted covers take the size in the URL,
 * YouTube video thumbnails switch to their largest variant (which some videos lack, so callers keep the original as a fallback).
 */
fun hiResArtwork(url: String?, size: Int = 1080): String? = when {
    url == null -> null
    "googleusercontent.com" in url || "ggpht.com" in url ->
        url.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size").replace(Regex("=s\\d+(?=-|$)"), "=s$size")
    "i.ytimg.com/vi" in url -> url.replace(Regex("/[a-z0-9_]*default\\.jpg.*$"), if (size > 480) "/maxresdefault.jpg" else "/hqdefault.jpg")
    else -> url
}
