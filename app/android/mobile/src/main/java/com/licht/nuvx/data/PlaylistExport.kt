package com.licht.nuvx.data

import com.licht.nuvx.data.model.Song

// Extended M3U: most players and importers (VLC, foobar2000, Soundiiz) read it.
object PlaylistExport {
    fun m3u(title: String, songs: List<Song>): String = buildString {
        append("#EXTM3U\n#PLAYLIST:").append(oneLine(title)).append('\n')
        songs.forEach { song ->
            val name = listOf(song.artist, song.title).filter(String::isNotBlank).joinToString(" - ")
            append("#EXTINF:-1,").append(oneLine(name)).append('\n')
            append("https://music.youtube.com/watch?v=").append(song.videoId).append('\n')
        }
    }

    // RFC 4180 CSV for spreadsheets and importers that take title/artist columns.
    fun csv(songs: List<Song>): String = buildString {
        append("title,artist,videoId,url\r\n")
        songs.forEach { song ->
            listOf(song.title, song.artist, song.videoId, "https://music.youtube.com/watch?v=${song.videoId}")
                .joinTo(this, ",", postfix = "\r\n", transform = ::csvField)
        }
    }

    // Titles come from YouTube, so a leading = + - @ must not run as a spreadsheet formula.
    private fun csvField(text: String): String {
        val safe = if (text.firstOrNull()?.let { it in "=+-@\t\r" } == true) "'$text" else text
        return if (safe.any { it in ",\"\r\n" }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun oneLine(text: String) = text.replace(Regex("[\\r\\n]+"), " ").trim()
}
