package com.bxane.nuvx.core

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in catalog integration check: NUVX_LIVE=1. Logs counts only; never continuation tokens or media URLs. */
class LiveArtistVideoPaginationTest {
    @Test fun artistVideoBrowseAndContinuationReturnVideosWhenAvailable() = runBlocking {
        assumeTrue("Set NUVX_LIVE=1 to contact YouTube Music", System.getenv("NUVX_LIVE") == "1")

        val backend = InnerTubeXBackend()
        val candidates = listOf("Daft Punk", "Taylor Swift", "Billie Eilish", "The Weeknd", "BTS")
        val artistShelves = candidates.flatMap { query ->
            val artistRef = backend.search(query, SearchFilter.ARTISTS).collections
                .firstOrNull { it.kind == Collection.Kind.ARTIST } ?: return@flatMap emptyList()
            val artist = backend.artist(artistRef.browseId)
            artist.videoShelves.map { artist to it }
        }
        val shelves = artistShelves.map { it.second }
        artistShelves.firstOrNull()?.first?.let { artist ->
            nuvxHttpClient().use { http ->
                val innerTube = InnerTube(http)
                val root = Json.parseToJsonElement(
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = artist.browseId).bodyAsText(),
                )
                val rawVideoShelves = root.elementsNamed("musicCarouselShelfRenderer").filterIsInstance<JsonObject>()
                    .filter { it.carouselTitle() in artist.videoShelves.map { shelf -> shelf.title } }
                val moreShelf = rawVideoShelves.firstOrNull { it.elementsNamed("moreContentButton").isNotEmpty() }
                val action = moreShelf?.elementsNamed("moreContentButton")?.firstOrNull()?.elementsNamed("browseEndpoint")
                        ?.filterIsInstance<JsonObject>()?.firstOrNull()
                val browseId = (action?.get("browseId") as? JsonPrimitive)?.contentOrNull
                val params = (action?.get("params") as? JsonPrimitive)?.contentOrNull
                val browseBody = browseId?.let {
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = it, params = params).bodyAsText()
                }
                val browsePage = browseBody?.let(Json::parseToJsonElement)
                val browseTracks = browseBody?.let { YtmJson.parseSearch(it, videoResults = true).tracks }.orEmpty()
                val shelfTitle = moreShelf?.carouselTitle()
                val parsedShelf = artist.videoShelves.firstOrNull { it.title == shelfTitle }
                val parsedPage = parsedShelf?.takeIf { browseId != null }?.let { shelf ->
                    YtmJson.parseArtistVideoPage(artist.browseId, artist.name, shelf.title, requireNotNull(browseBody), requireNotNull(browseId))
                }
                val moreButtons = rawVideoShelves.flatMap { it.elementsNamed("moreContentButton") }
                val continuationPath = browsePage?.pathsNamed("continuationItemRenderer")?.firstOrNull()
                println("artistRawShelfShape: carouselShelves=${root.elementsNamed("musicCarouselShelfRenderer").size}; nextContinuationData=${root.elementsNamed("nextContinuationData").size}; continuationItems=${root.elementsNamed("continuationItemRenderer").size}; parsedVideoShelves=${rawVideoShelves.size}; videoShelvesWithMoreButton=${rawVideoShelves.count { it.elementsNamed("moreContentButton").isNotEmpty() }}; videoMoreButtonsWithBrowseTarget=${moreButtons.count { it.elementsNamed("browseEndpoint").isNotEmpty() }}")
                println("artistVideoBrowseTarget: present=${browseId != null}; paramsPresent=${params != null}; carouselShelves=${browsePage?.elementsNamed("musicCarouselShelfRenderer")?.size ?: 0}; musicShelves=${browsePage?.elementsNamed("musicShelfRenderer")?.size ?: 0}; responsiveRows=${browsePage?.elementsNamed("musicResponsiveListItemRenderer")?.size ?: 0}; twoRowCards=${browsePage?.elementsNamed("musicTwoRowItemRenderer")?.size ?: 0}; parsedVideoTracks=${browseTracks.size}; parsedShelfVideos=${parsedPage?.items?.size ?: 0}; continuationData=${browsePage?.elementsNamed("nextContinuationData")?.size ?: 0}; continuationItems=${browsePage?.elementsNamed("continuationItemRenderer")?.size ?: 0}; continuationPath=${continuationPath ?: "none"}; parsedShelfContinuation=${parsedPage?.continuation != null}")
            }
            val shelfTitle = artist.videoShelves.firstOrNull { it.moreBrowseId != null }?.title
            val browseShelf = assertNotNull(artist.videoShelves.firstOrNull { it.title == shelfTitle })
            assumeTrue("Artist video shelf has no public more-content browse target", browseShelf.moreBrowseId != null)
            val browseResult = backend.artistVideoPage(artist.browseId, artist.name, browseShelf)
            println("artistVideoBrowseBackend: returnedVideos=${browseResult.items.size}; nextPageAvailable=${browseResult.continuation != null}")
            assertTrue(browseResult.items.isNotEmpty(), "artist video more-content browse returned no parseable videos")
            if (browseResult.continuation != null) {
                val continuationPage = backend.artistVideoPage(artist.browseId, artist.name, browseResult)
                println("artistVideoContinuationBackend: returnedVideos=${continuationPage.items.size}; nextPageAvailable=${continuationPage.continuation != null}")
                assertTrue(continuationPage.items.isNotEmpty(), "artist video continuation returned no parseable videos")
            }
        }
        println("artistVideoPagination: artistCandidates=${candidates.size}; videoShelves=${shelves.size}; shelvesWithVideos=${shelves.count { it.items.isNotEmpty() }}; shelvesWithContinuation=${shelves.count { it.items.isNotEmpty() && it.continuation != null }}")
        artistShelves.firstOrNull { (_, shelf) -> shelf.items.isNotEmpty() && shelf.continuation != null }?.let { (artist, shelf) ->
            val page = backend.artistVideoPage(artist.browseId, artist.name, shelf)
            println("artistVideoContinuation: firstPageVideos=${shelf.items.size}; nextPageVideos=${page.items.size}; nextContinuation=${page.continuation != null}")
            assertTrue(page.items.isNotEmpty(), "artist-video continuation returned no parseable videos")
        }
        Unit
    }

    private fun JsonElement.elementsNamed(key: String): List<JsonElement> = buildList {
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (name, child) ->
                    if (name == key) add(child)
                    visit(child)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(this@elementsNamed)
    }

    private fun JsonObject.carouselTitle(): String? =
        (((this["header"] as? JsonObject)?.get("musicCarouselShelfBasicHeaderRenderer") as? JsonObject)
            ?.get("title") as? JsonObject)?.get("runs")?.let { runs ->
            (runs as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                ?.joinToString("")
        }

    private fun JsonElement.pathsNamed(key: String): List<String> = buildList {
        fun visit(value: JsonElement, path: String) {
            when (value) {
                is JsonObject -> value.forEach { (name, child) ->
                    val childPath = if (path.isEmpty()) name else "$path.$name"
                    if (name == key) add(childPath)
                    visit(child, childPath)
                }
                is JsonArray -> value.forEach { visit(it, path) }
                else -> Unit
            }
        }
        visit(this@pathsNamed, "")
    }
}
