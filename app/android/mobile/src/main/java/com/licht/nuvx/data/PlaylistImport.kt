package com.licht.nuvx.data

import com.licht.nuvx.data.model.Song

// Reads what PlaylistExport writes (and other players' M3U/CSV) back into songs, in file order.
// shortcut: only entries carrying a YouTube link or video id are kept; title/artist-only rows are counted as unmatched instead of searched. Add search matching if people import from other services' files.
object PlaylistImport {
    data class Result(val title: String?, val songs: List<Song>, val unmatched: Int)

    const val MAX_ENTRIES = 10_000

    private val idInUrl = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val bareId = Regex("""[A-Za-z0-9_-]{11}""")

    fun parse(text: String): Result {
        val body = text.removePrefix("﻿")
        return if (body.trimStart().startsWith("#") || body.lineSequence().firstOrNull()?.contains(',') != true) m3u(body) else csv(body)
    }

    private fun m3u(text: String): Result {
        var title: String? = null
        var name: String? = null
        val songs = mutableListOf<Song>()
        var unmatched = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:") -> title = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTINF:") -> name = line.substringAfter(',', "").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val id = idInUrl.find(line)?.groupValues?.get(1)
                    if (id == null) unmatched++ else if (songs.size < MAX_ENTRIES) songs += song(id, name)
                    name = null
                }
            }
        }
        return Result(title, songs, unmatched)
    }

    private fun csv(text: String): Result {
        val rows = csvRows(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return Result(null, emptyList(), 0)
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val titleCol = col("title", "name", "track", "track name", "song")
        val artistCol = col("artist", "artists", "artist name", "artist name(s)")
        val idCol = col("videoid", "video id", "video_id", "id")
        val urlCol = col("url", "link", "youtube url")
        val songs = mutableListOf<Song>()
        var unmatched = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            fun cell(i: Int) = row.getOrNull(i)?.let(::unFormula)?.trim().orEmpty()
            val id = cell(idCol).takeIf(bareId::matches) ?: idInUrl.find(cell(urlCol))?.groupValues?.get(1)
            if (id == null) {
                unmatched++
            } else if (songs.size < MAX_ENTRIES) {
                songs += Song(videoId = id, title = cell(titleCol).ifEmpty { id }, artist = cell(artistCol), thumbnailUrl = thumbnail(id))
            }
        }
        return Result(null, songs, unmatched)
    }

    private fun song(id: String, extinf: String?): Song {
        val artist = extinf?.substringBefore(" - ", "")?.trim().orEmpty()
        val title = extinf?.let { if (artist.isEmpty()) it else it.substringAfter(" - ") }?.trim().orEmpty()
        return Song(videoId = id, title = title.ifEmpty { id }, artist = artist, thumbnailUrl = thumbnail(id))
    }

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
