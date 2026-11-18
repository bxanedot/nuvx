package com.licht.nuvx.data

import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Reads what PlaylistExport writes (and other players' or services' M3U/CSV) back into songs, in file order.
object PlaylistImport {
    sealed interface Entry {
        /** Carries a YouTube link or video id. */
        data class Found(val song: Song) : Entry

        /** Only a title (and maybe artist/duration): has to be looked up. */
        data class Wanted(val title: String, val artist: String, val durationSec: Int?) : Entry
    }

    data class Result(val title: String?, val entries: List<Entry>, val unmatched: Int) {
        val songs: List<Song> get() = entries.filterIsInstance<Entry.Found>().map { it.song }
        val wanted: Int get() = entries.count { it is Entry.Wanted }
    }

    const val MAX_ENTRIES = 10_000

    private val idInUrl = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val bareId = Regex("""[A-Za-z0-9_-]{11}""")

    fun parse(text: String): Result {
        val body = text.removePrefix("﻿")
        return if (body.trimStart().startsWith("#") || body.lineSequence().firstOrNull()?.contains(',') != true) m3u(body) else csv(body)
    }

    /**
     * Looks up the [Entry.Wanted] ones with [match] (a few at a time) and returns the songs in file order,
     * plus how many could not be matched. A null from [match] means "no confident match": that entry is left out.
     */
    suspend fun resolve(entries: List<Entry>, match: suspend (Entry.Wanted) -> Song?): Pair<List<Song>, Int> = coroutineScope {
        val gate = Semaphore(MATCH_PARALLELISM)
        val resolved = entries.map { entry ->
            async {
                when (entry) {
                    is Entry.Found -> entry.song
                    is Entry.Wanted -> gate.withPermit { runCatching { match(entry) }.getOrNull() }
                }
            }
        }.awaitAll()
        resolved.filterNotNull() to resolved.count { it == null }
    }

    private const val MATCH_PARALLELISM = 4

    private fun m3u(text: String): Result {
        var title: String? = null
        var extinf: Pair<String, Int?>? = null
        val entries = mutableListOf<Entry>()
        var unmatched = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:") -> title = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTINF:") -> extinf = line.substringAfter(',', "").trim() to
                    line.removePrefix("#EXTINF:").substringBefore(',').trim().toIntOrNull()?.takeIf { it > 0 }
                line.startsWith("#") -> Unit
                else -> {
                    val id = idInUrl.find(line)?.groupValues?.get(1)
                    val (artist, name) = splitName(extinf?.first)
                    when {
                        entries.size >= MAX_ENTRIES -> Unit
                        id != null -> entries += Entry.Found(song(id, name, artist))
                        name.isNotEmpty() -> entries += Entry.Wanted(name, artist, extinf?.second)
                        else -> unmatched++
                    }
                    extinf = null
                }
            }
        }
        return Result(title, entries, unmatched)
    }

    private fun csv(text: String): Result {
        val rows = csvRows(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return Result(null, emptyList(), 0)
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val titleCol = col("title", "name", "track", "track name", "song")
        val artistCol = col("artist", "artists", "artist name", "artist name(s)")
        val idCol = col("videoid", "video id", "video_id", "id")
        val urlCol = col("url", "link", "youtube url")
        val msCol = col("duration (ms)", "duration_ms", "track duration (ms)")
        val entries = mutableListOf<Entry>()
        var unmatched = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            fun cell(i: Int) = row.getOrNull(i)?.let(::unFormula)?.trim().orEmpty()
            val id = cell(idCol).takeIf(bareId::matches) ?: idInUrl.find(cell(urlCol))?.groupValues?.get(1)
            val title = cell(titleCol)
            // Spotify exports list several artists as "A, B"; the first one is what search needs.
            val artist = cell(artistCol)
            when {
                entries.size >= MAX_ENTRIES -> Unit
                id != null -> entries += Entry.Found(
                    Song(videoId = id, title = title.ifEmpty { id }, artist = artist, thumbnailUrl = thumbnail(id)),
                )
                title.isNotEmpty() -> entries += Entry.Wanted(
                    title,
                    artist.substringBefore(',').trim(),
                    cell(msCol).toLongOrNull()?.div(1000)?.toInt()?.takeIf { it > 0 },
                )
                else -> unmatched++
            }
        }
        return Result(null, entries, unmatched)
    }

    /** "Artist - Title" as PlaylistExport writes it; a name without " - " is all title. */
    private fun splitName(extinf: String?): Pair<String, String> {
        val artist = extinf?.substringBefore(" - ", "")?.trim().orEmpty()
        val title = extinf?.let { if (artist.isEmpty()) it else it.substringAfter(" - ") }?.trim().orEmpty()
        return artist to title
    }

    private fun song(id: String, title: String, artist: String) =
        Song(videoId = id, title = title.ifEmpty { id }, artist = artist, thumbnailUrl = thumbnail(id))

    private fun thumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    // PlaylistExport prefixes a ' to cells that would run as spreadsheet formulas; undo just that.
    private fun unFormula(cell: String) = if (cell.length > 1 && cell[0] == '\'' && cell[1] in "=+-@\t\r") cell.substring(1) else cell

    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length && rows.size <= MAX_ENTRIES) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
