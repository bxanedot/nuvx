package com.bxane.nuvx.data.import

import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.model.PlaylistPrivacy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put








object SpotifyImportBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun interface ResultCallback {
        fun onResult(json: String?, message: String?)
    }


    fun playlists(callback: ResultCallback) = deliver(callback) {
        val items = SpotifyImport.loadPlaylists().map { summary ->
            buildJsonObject {
                put("id", summary.id)
                put("name", summary.name)
                put("trackCount", summary.trackCount)
            }
        }
        buildJsonObject { put("playlists", JsonArray(items)) }.toString()
    }


    fun playlist(input: String, callback: ResultCallback) = deliver(callback) {
        val id = SpotifyImport.playlistId(input)
            ?: error("That link is not a Spotify playlist. Paste a playlist link or its id.")
        val playlist = SpotifyImport.loadPlaylist(id)
        buildJsonObject {
            put("id", playlist.id)
            put("name", playlist.name)
            put(
                "tracks",
                JsonArray(playlist.tracks.map { track ->
                    buildJsonObject {
                        put("title", track.title)
                        put("artists", track.artists)
                        put("durationMs", track.durationMs)
                        put("album", track.album)
                    }
                }),
            )
        }.toString()
    }


    fun match(title: String, artists: String, durationMs: Long, callback: ResultCallback) =
        deliver(callback) {
            val match = SpotifyImport.matchTrack(
                SpotifyImport.Track(title, artists, durationMs, album = null),
            )
            buildJsonObject {
                put("status", match.status.name)
                put("videoId", match.videoId)
                put("title", match.title)
                put("artist", match.artist)
                put("durationText", match.durationText)
                put("thumbnailUrl", match.thumbnailUrl)
                put("confidence", match.confidence)
            }.toString()
        }


    fun createPlaylist(
        name: String,
        privacy: String,
        videoIds: List<String>,
        callback: ResultCallback,
    ) = deliver(callback) {
        val generation = Innertube.sessionGeneration
        Innertube.checkSession(generation)
        val policy = PlaylistPrivacy.entries.firstOrNull { it.name.equals(privacy, ignoreCase = true) }
            ?: PlaylistPrivacy.PRIVATE
        val id = Innertube.createPlaylist(name, policy, videoIds = videoIds)
        Innertube.checkSession(generation)
        buildJsonObject { put("playlistId", id) }.toString()
    }

    private fun deliver(callback: ResultCallback, block: suspend () -> String) {
        scope.launch {
            try {
                callback.onResult(block(), null)
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }
}
