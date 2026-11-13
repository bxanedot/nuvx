package com.bxane.nuvx.data.sources

import com.bxane.nuvx.data.settings.SecretRead


internal class SourceListStorage(
    private val readPrimary: () -> String,
    private val writePrimary: (String) -> Unit,
    private val readBackup: () -> SecretRead,
    private val writeBackup: (String?) -> Boolean,
    private val isValid: (String) -> Boolean,
) {
    fun read(): String {
        val primary = readPrimary()

        if (primary.isBlank()) return primary
        if (isValid(primary)) {

            runCatching {
                val backup = readBackup()
                if (backup.available && backup.value?.let(isValid) != true) writeBackup(primary)
            }
            return primary
        }
        val backup = readBackup()
        return backup.value?.takeIf { backup.available && isValid(it) }
            ?: error("Sources could not be read. Unlock your device or Keychain and try again.")
    }

    fun write(document: String) {
        check(isValid(document)) { "Could not save the source list" }
        val before = readBackup()
        check(before.available && writeBackup(document)) {
            "Could not securely save sources. Unlock your device or Keychain and try again."
        }
        try {
            writePrimary(document)
        } catch (failure: Exception) {
            runCatching { writeBackup(before.value) }
            throw failure
        }
    }
}
