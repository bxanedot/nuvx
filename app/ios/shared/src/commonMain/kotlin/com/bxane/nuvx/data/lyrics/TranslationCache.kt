package com.bxane.nuvx.data.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json













internal object TranslationCache {

    private val json = Json { ignoreUnknownKeys = true }


    private const val MEMORY_ENTRIES = 12

    private val memory = BoundedLru<String, String>(MEMORY_ENTRIES)

    fun get(key: String): CachedTranslation? {
        memory[key]?.let { document ->
            return runCatching {
                json.decodeFromString(CachedTranslation.serializer(), document)
            }.getOrNull()
        }
        val stored = read(key) ?: return null
        memory[key] = stored
        return runCatching {
            json.decodeFromString(CachedTranslation.serializer(), stored)
        }.getOrNull()
    }

    fun put(key: String, entry: CachedTranslation) {
        val document = runCatching {
            json.encodeToString(CachedTranslation.serializer(), entry)
        }.getOrNull() ?: return
        memory[key] = document
        write(key, document)
    }

    fun clear() {
        memory.clear()
        removeAll()
    }


    internal fun size(): Int = memory.size
}








@Serializable
internal data class CachedTranslation(
    val version: Int,
    val sourceLanguage: String,
    val targetLanguage: String,
    val texts: List<String>,
)


internal expect fun read(key: String): String?
internal expect fun write(key: String, document: String)
internal expect fun removeAll()
