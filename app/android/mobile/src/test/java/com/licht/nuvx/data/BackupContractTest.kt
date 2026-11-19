package com.licht.nuvx.data

import com.licht.nuvx.data.stats.Backup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupContractTest {
    @Test
    fun `reads the shared backup case the desktop reads`() {
        val file = java.io.File("../../../core/contracts/backup-v1.json").takeIf { it.isFile }
            ?: java.io.File("core/contracts/backup-v1.json")
        val backup = Json { ignoreUnknownKeys = true }.decodeFromString(Backup.BackupFile.serializer(), file.readText())
        assertEquals("nuvx", backup.app)
        assertEquals(listOf("nuvx_local_a1", "nuvx_local_b2"), backup.playlists.map { it.id })
        // The desktop drops the song whose id is not an 11-character YouTube id; the phone keeps all three.
        assertEquals(listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk", "bad"), backup.playlists[0].songs.map { it.videoId })
        assertEquals("3:33", backup.playlists[0].songs[0].durationText)
    }
}
