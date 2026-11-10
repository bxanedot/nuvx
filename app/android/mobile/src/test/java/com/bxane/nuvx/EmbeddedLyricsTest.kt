package com.bxane.nuvx

import com.bxane.nuvx.data.lyrics.EmbeddedLyrics
import com.bxane.nuvx.data.lyrics.LrcLib
import com.bxane.nuvx.data.lyrics.LyricLine
import com.bxane.nuvx.data.lyrics.LyricWord
import com.bxane.nuvx.data.lyrics.toEnhancedLrc
import com.bxane.nuvx.data.lyrics.toLrc
import com.bxane.nuvx.download.FlacTagger
import com.bxane.nuvx.download.Mp4Tagger
import com.bxane.nuvx.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class EmbeddedLyricsTest {

    private val lrc = "[00:01.00]first line\n[00:05.00]second line"

    @Test
    fun `an m4a written by the tagger reads back`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a flac written by the tagger reads back`() {
        val tagged = FlacTagger.tag(
            bytes = minimalFlac(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a webm written by the tagger reads back`() {
        val tagged = WebmTagger.tag(
            bytes = minimalWebm(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a cover alongside the lyrics is not mistaken for them`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = ByteArray(64) { 0x7F },
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `word timings survive the write and the read, in all three containers`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(startMs = 1_000L, endMs = 1_400L, text = "two"),
                    LyricWord(startMs = 1_400L, endMs = 2_000L, text = "words"),
                ),
            ),
        )
        val plain = words.toLrc()
        val enhanced = words.toEnhancedLrc()

        val written = listOf(
            Mp4Tagger.tag(minimalMp4(), "t", "a", null, plain, null, false, enhanced),
            FlacTagger.tag(minimalFlac(), "t", "a", null, plain, null, "image/jpeg", enhanced),
            WebmTagger.tag(minimalWebm(), "t", "a", null, plain, null, "image/jpeg", enhanced),
        )
        for (bytes in written) {
            val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.fromBytes(bytes)))
            assertEquals(1, read.size)
            assertEquals("two words", read[0].text)
            assertEquals(listOf("two", "words"), read[0].words.map { it.text })
            assertEquals(listOf(1_000L, 1_400L), read[0].words.map { it.startMs })
            assertEquals(2_000L, read[0].endMs)
        }
    }

    @Test
    fun `the portable field never carries word stamps`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        assertEquals("[00:01.00]two words", words.toLrc())
    }

    @Test
    fun `a file with no lyrics at all reads back as nothing`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = null,
            cover = null,
            coverIsPng = false,
        )
        assertNull(EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `something that is none of the three containers is not guessed at`() {
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(512) { it.toByte() }))
    }

    @Test
    fun `a package's lyrics come from the sidecar beside its playlist`() {
        val root = packageDir("lyrics.lrc" to lrc)
        assertEquals(lrc, EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path))
    }

    @Test
    fun `word timings survive a sidecar too`() {
        val words = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "two words",
                words = listOf(
                    LyricWord(1_000L, 1_400L, "two"),
                    LyricWord(1_400L, 2_000L, "words"),
                ),
            ),
        )
        val root = packageDir("lyrics.lrc" to words.toEnhancedLrc())
        val read = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.sidecar(File(root, "playlist.m3u8").path)))
        assertEquals(listOf("two", "words"), read.single().words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L), read.single().words.map { it.startMs })
    }

    @Test
    fun `a package with no lyrics, and an ordinary file, are left to the container reader`() {
        assertNull(EmbeddedLyrics.sidecar(File(packageDir("lyrics.lrc" to "  \n"), "playlist.m3u8").path))
        assertNull(EmbeddedLyrics.sidecar(File(packageDir(), "playlist.m3u8").path))
        assertNull(EmbeddedLyrics.sidecar(File(packageDir("lyrics.lrc" to lrc), "song.flac").path))
        assertNull(EmbeddedLyrics.sidecar(null))
    }

    private fun packageDir(vararg files: Pair<String, String>): File {
        val root = createTempDirectory("offline").toFile().also(File::deleteOnExit)
        File(root, "playlist.m3u8").writeText("#EXTM3U\n#EXT-X-ENDLIST\n")
        files.forEach { (name, text) -> File(root, name).writeText(text) }
        return root
    }

    private fun minimalMp4(): ByteArray = box("ftyp", "isom".toByteArray()) + box("moov", ByteArray(0))

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun minimalFlac(): ByteArray {
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() +
            byteArrayOf(0x80.toByte(), 0, 0, streamInfo.size.toByte()) + streamInfo +
            byteArrayOf(0xFF.toByte())
    }

    private fun minimalWebm(): ByteArray {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) +
            byteArrayOf(0x84.toByte()) + ByteArray(4)
        val body = ByteArray(8)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67) +
            byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, body.size.toByte()) + body
        return header + segment
    }
}
