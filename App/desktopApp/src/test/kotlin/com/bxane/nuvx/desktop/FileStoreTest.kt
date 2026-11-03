package com.bxane.nuvx.desktop

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStoreTest {
    private val file = File(Files.createTempDirectory("nuvx-store").toFile(), "settings.properties")

    @Test fun keepsValuesFarOverThePreferencesLimitAcrossRestarts() {
        val history = "x".repeat(40_000) // Preferences allows 8 KB per value; 50 played songs are ~20 KB.
        FileStore(file).put("history.played", history)
        val reopened = FileStore(file)
        assertEquals(history, reopened.get("history.played"))
        reopened.put("history.played", null)
        assertNull(FileStore(file).get("history.played"))
    }

    @Test fun copiesOldPreferencesOnceOnFirstRun() {
        val legacy = Preferences.userRoot().node("nuvx-test-${UUID.randomUUID()}")
        try {
            legacy.put("welcomeSeen", "true")
            assertEquals("true", FileStore(file, legacy).get("welcomeSeen"))
            legacy.put("welcomeSeen", "false")
            assertEquals("true", FileStore(file, legacy).get("welcomeSeen"), "the file wins once it exists")
        } finally { legacy.removeNode() }
    }
}
