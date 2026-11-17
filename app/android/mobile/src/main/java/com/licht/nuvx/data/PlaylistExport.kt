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

    private fun oneLine(text: String) = text.replace(Regex("[\\r\\n]+"), " ").trim()
}
