package com.licht.nuvx.download

import com.licht.nuvx.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The songs waiting to download, kept on disk so a queue survives Android killing the app. */
internal object SavedDownloadQueue {

    fun encode(songs: List<Song>): String = JsonArray(songs.map { song ->
        JsonObject(buildMap {
            put("videoId", JsonPrimitive(song.videoId))
            put("title", JsonPrimitive(song.title))
            put("artist", JsonPrimitive(song.artist))
            song.thumbnailUrl?.let { put("thumbnailUrl", JsonPrimitive(it)) }
            song.durationText?.let { put("durationText", JsonPrimitive(it)) }
            song.artistId?.let { put("artistId", JsonPrimitive(it)) }
            song.albumId?.let { put("albumId", JsonPrimitive(it)) }
            song.albumName?.let { put("albumName", JsonPrimitive(it)) }
            if (song.isVideo) put("isVideo", JsonPrimitive(true))
        })
    }).toString()

    fun decode(text: String): List<Song> = runCatching {
        Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val o = element.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            val videoId = str("videoId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Song(
                videoId = videoId,
                title = str("title").orEmpty(),
                artist = str("artist").orEmpty(),
                thumbnailUrl = str("thumbnailUrl"),
                durationText = str("durationText"),
                artistId = str("artistId"),
                albumId = str("albumId"),
                albumName = str("albumName"),
                isVideo = o["isVideo"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }.getOrDefault(emptyList())

    // Write then rename, so a crash mid-write never leaves a half file.
    fun write(file: File, songs: List<Song>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(encode(songs))
        if (!temp.renameTo(file)) temp.delete()
    }

    fun read(file: File): List<Song> = if (file.isFile) decode(file.readText()) else emptyList()
}
