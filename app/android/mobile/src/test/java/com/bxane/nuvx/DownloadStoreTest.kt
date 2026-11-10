package com.bxane.nuvx

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.StreamRequest
import com.bxane.nuvx.download.DownloadStore
import com.bxane.nuvx.download.Downloader
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.download.SavedSongMetadata
import com.bxane.nuvx.download.resolvedDownloadDates
import com.bxane.nuvx.download.youtubeDownloadBadge
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoreTest {

    private fun song(title: String, artist: String, videoId: String = "abc123") =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `old download metadata remains readable without an added date`() {
        val old = Json.decodeFromString<SavedSongMetadata>(
            """{"videoId":"abc","title":"Song","artist":"Artist","uri":"file:///song.webm"}""",
        )

        assertNull(old.dateAddedSeconds)
    }

    @Test
    fun `existing downloads use file time when no creation time was recorded`() {
        val (added, modified) = resolvedDownloadDates(
            persistedAddedSeconds = null,
            fileModifiedMillis = 1_725_000_123_999,
        )

        assertEquals(1_725_000_123L, added)
        assertEquals(1_725_000_123L, modified)
    }

    @Test
    fun `persisted creation time survives later file modifications`() {
        val (added, modified) = resolvedDownloadDates(
            persistedAddedSeconds = 1_700_000_000,
            fileModifiedMillis = 1_725_000_123_999,
        )

        assertEquals(1_700_000_000L, added)
        assertEquals(1_725_000_123L, modified)
    }

    @Test
    fun `flac and wav map to the types the media store actually accepts`() {
        val flac = DownloadStore.storable("flac")
        assertEquals("flac", flac?.extension)
        assertEquals("audio/flac", flac?.mimeType)

        val wav = DownloadStore.storable("wav")
        assertEquals("wav", wav?.extension)
        assertEquals("audio/x-wav", wav?.mimeType)
    }

    @Test
    fun `alac is filed as the mp4 it actually is`() {
        val alac = DownloadStore.storable("alac")
        assertEquals("m4a", alac?.extension)
        assertEquals("audio/mp4", alac?.mimeType)
    }

    @Test
    fun `codecs are matched however a source spells them`() {
        assertEquals("flac", DownloadStore.storable("FLAC")?.extension)
        assertEquals("flac", DownloadStore.storable(" x-flac ")?.extension)
    }

    @Test
    fun `an unknown or absent codec is nothing to file`() {
        assertNull(DownloadStore.storable(null))
        assertNull(DownloadStore.storable(""))
        assertNull(DownloadStore.storable("opus"))
        assertNull(DownloadStore.storable("webm"))
        assertNull(DownloadStore.storable("dsf"))
    }

    @Test
    fun `youtube downloads retain their codec and resolved bitrate`() {
        assertEquals("OPUS · 160 kbps", youtubeDownloadBadge("webm", 160))
        assertEquals("AAC · 128 kbps", youtubeDownloadBadge("m4a", 128))
        assertNull(youtubeDownloadBadge("webm", 0))
    }

    @Test
    fun `the name is artist then title, and carries the extension asked for`() {
        assertEquals(
            "Arijit Singh - Kesariya.flac",
            DownloadStore.fileNameFor(song("Kesariya", "Arijit Singh"), "flac"),
        )
    }

    @Test
    fun `characters a volume or a shell would object to are replaced`() {
        val name = DownloadStore.fileNameFor(song("A/B: C?", "D|E"), "m4a")
        assertEquals("D E - A B C.m4a", name)
    }

    @Test
    fun `a row with nothing to name it falls back to the video id`() {
        assertEquals("xyz789.m4a", DownloadStore.fileNameFor(song("", "", "xyz789"), "m4a"))
    }

    @Test
    fun `only lossless asks a source for the file it holds`() {
        assertEquals(
            StreamRequest.Lossless,
            SourceResolver.requestForDownload(DownloadQuality.LOSSLESS),
        )
        assertEquals(StreamRequest.Best, SourceResolver.requestForDownload(DownloadQuality.HIGH))
        assertEquals(
            StreamRequest.Capped(128),
            SourceResolver.requestForDownload(DownloadQuality.STANDARD),
        )
    }

    @Test
    fun `high is uncapped rather than capped very high`() {
        assertEquals(Int.MAX_VALUE, DownloadQuality.HIGH.maxKbps)
        assertTrue(SourceResolver.requestForDownload(DownloadQuality.HIGH) !is StreamRequest.Capped)
    }

    @Test
    fun `every download rung is describable`() {
        DownloadQuality.entries.forEach { quality ->
            assertTrue(quality.label.isNotBlank())
            assertTrue(quality.detail.isNotBlank())
            assertTrue(quality.perTrack.isNotBlank())
        }
    }

    @Test
    fun `wifi-only refuses metered connections and nothing else`() {
        val wifiOnly = AppSettings.wifiOnlyDownloads.value
        val metered = AppSettings.meteredConnection.value
        try {
            AppSettings.wifiOnlyDownloads.value = true
            AppSettings.meteredConnection.value = true
            assertFalse(AppSettings.downloadsAllowedNow)

            AppSettings.meteredConnection.value = false
            assertTrue(AppSettings.downloadsAllowedNow)

            AppSettings.meteredConnection.value = null
            assertTrue(AppSettings.downloadsAllowedNow)

            AppSettings.wifiOnlyDownloads.value = false
            AppSettings.meteredConnection.value = true
            assertTrue(AppSettings.downloadsAllowedNow)
        } finally {
            AppSettings.wifiOnlyDownloads.value = wifiOnly
            AppSettings.meteredConnection.value = metered
        }
    }

    @Test
    fun `a capped streaming ceiling no longer decides what a download keeps`() {
        val cellular = AppSettings.audioQualityCellular.value
        val metered = AppSettings.meteredConnection.value
        try {
            AppSettings.audioQualityCellular.value = AudioQuality.LOW
            AppSettings.meteredConnection.value = true
            assertEquals(AudioQuality.LOW, AppSettings.effectiveAudioQuality)
            assertEquals(
                StreamRequest.Lossless,
                SourceResolver.requestForDownload(DownloadQuality.LOSSLESS),
            )
        } finally {
            AppSettings.audioQualityCellular.value = cellular
            AppSettings.meteredConnection.value = metered
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun sniffed(head: ByteArray, contentType: String = "", stated: String? = null) =
        Downloader.sniff(head, contentType, stated, totalBytes = null)

    private fun containerOf(head: ByteArray, stated: String? = null) =
        (sniffed(head, stated = stated) as? Downloader.Probe.File)?.container

    @Test
    fun `a source file is filed by what its opening bytes are`() {
        assertEquals(Downloader.Container.FLAC, containerOf("fLaC\u0000\u0000".toByteArray()))
        assertEquals(Downloader.Container.MP4, containerOf("\u0000\u0000\u0000 ftypM4A ".toByteArray()))
        assertEquals(Downloader.Container.WAV, containerOf("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertEquals(Downloader.Container.OGG, containerOf("OggS\u0000\u0002".toByteArray()))
        assertEquals(Downloader.Container.WEBM, containerOf(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals(Downloader.Container.ADTS, containerOf(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals(Downloader.Container.MP3, containerOf(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(Downloader.Container.MP3, containerOf("ID3\u0004\u0000".toByteArray()))
        assertEquals(Downloader.Container.ADTS, containerOf("ID3\u0004\u0000".toByteArray(), stated = "aac"))
        assertEquals("m4a", Downloader.Container.MP4.extension)
        assertEquals("audio/mpeg", Downloader.Container.MP3.mimeType)
    }

    @Test
    fun `a manifest is recognised whatever its URL or label says`() {
        assertEquals(
            Downloader.Probe.Manifest(dash = false),
            sniffed("#EXTM3U\n#EXT-X-VERSION:7\n".toByteArray(), stated = "flac"),
        )
        assertEquals(
            Downloader.Probe.Manifest(dash = false),
            sniffed("﻿\n#EXTM3U\n".toByteArray()),
        )
        assertEquals(
            Downloader.Probe.Manifest(dash = true),
            sniffed("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=".toByteArray()),
        )
        assertEquals(
            Downloader.Probe.Manifest(dash = false),
            sniffed("garbage".toByteArray(), contentType = "application/vnd.apple.mpegurl"),
        )
    }

    @Test
    fun `bytes that are no audio container are not kept`() {
        assertNull(sniffed("<html><body>Not found".toByteArray()))
        assertNull(sniffed(ByteArray(0)))
        assertNull(sniffed("FORM\u0000\u0000\u0000\u0000AIFFCOMM".toByteArray()))
    }

    @Test
    fun `an unstated bitrate is measured from the file's size`() {
        assertEquals(320, Downloads.estimatedKbps(9_600_000, 240))
        assertNull(Downloads.estimatedKbps(null, 240))
        assertNull(Downloads.estimatedKbps(9_600_000, null))
        assertNull(Downloads.estimatedKbps(9_600_000, 0))
    }
}
