package com.licht.nuvx.data

import com.licht.nuvx.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistExportTest {
    @Test
    fun writesExtendedM3uWithOneEntryPerSong() {
        val songs = listOf(
            Song(videoId = "abc", title = "Song", artist = "Artist", thumbnailUrl = null),
            Song(videoId = "def", title = "Line\nbreak", artist = "", thumbnailUrl = null),
        )
        assertEquals(
            "#EXTM3U\n#PLAYLIST:Road\n" +
                "#EXTINF:-1,Artist - Song\nhttps://music.youtube.com/watch?v=abc\n" +
                "#EXTINF:-1,Line break\nhttps://music.youtube.com/watch?v=def\n",
            PlaylistExport.m3u("Road", songs),
        )
    }
}
