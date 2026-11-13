package com.bxane.nuvx.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue








class SourceRegistryInitTest {

    private fun config(kind: SourceKind, id: String = kind.name, enabled: Boolean = kind != SourceKind.JIOSAAVN) =
        SourceConfig(id = id, kind = kind, enabled = enabled)



    @Test
    fun `a fresh install gets exactly the built-in kinds`() {
        val seeded = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = true)
        assertEquals(
            listOf(SourceKind.JIOSAAVN, SourceKind.YOUTUBE),
            seeded.map { it.kind },
        )
    }

    @Test
    fun `seeding does not duplicate a kind already stored`() {
        val stored = listOf(config(SourceKind.YOUTUBE), config(SourceKind.ADDON))
        val seeded = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = true)
        assertEquals(1, seeded.count { it.kind == SourceKind.YOUTUBE })
        assertEquals(1, seeded.count { it.kind == SourceKind.ADDON })
    }



    @Test
    fun `YouTube cannot be switched off and an old install that had it off gets it back`() {
        val stored = listOf(config(SourceKind.YOUTUBE, enabled = false))
        val seeded = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = true)


        assertTrue(seeded.first { it.kind == SourceKind.YOUTUBE }.enabled)
    }



    @Test
    fun `JioSaavn is forced off once whatever was stored`() {
        val stored = listOf(config(SourceKind.JIOSAAVN, enabled = true))
        val seeded = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = true)
        assertTrue(!seeded.first { it.kind == SourceKind.JIOSAAVN }.enabled)
    }

    @Test
    fun `a deliberate opt-in survives after the marker is written`() {
        val stored = listOf(config(SourceKind.JIOSAAVN, enabled = true))
        val seeded = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = false)
        assertTrue(seeded.first { it.kind == SourceKind.JIOSAAVN }.enabled)
    }



    @Test
    fun `the retired built-in module goes and a custom one stays`() {


        val stored = listOf(
            config(SourceKind.MODULE, id = "builtin"),
            config(SourceKind.CUSTOM_MODULE, id = "mine", enabled = true),
        )
        val seeded = SourceRegistry.sourcesForInit(stored, forceJioSaavnOff = true)
        assertNull(seeded.firstOrNull { it.kind == SourceKind.MODULE })
        assertEquals("mine", seeded.firstOrNull { it.kind == SourceKind.CUSTOM_MODULE }?.id)
    }



    @Test
    fun `a module index from the old settings screen becomes a source`() {
        val seeded = SourceRegistry.sourcesForInit(
            emptyList(), forceJioSaavnOff = true,
            legacyModuleIndexUrl = "https://example.invalid/index.json",
        )
        val migrated = seeded.first { it.kind == SourceKind.CUSTOM_MODULE }
        assertEquals("https://example.invalid/index.json", migrated.baseUrl)


        assertTrue(migrated.enabled)
    }

    @Test
    fun `the legacy key is a one-shot and never joins a later source`() {


        val stored = listOf(config(SourceKind.CUSTOM_MODULE, id = "mine", enabled = true))
        val seeded = SourceRegistry.sourcesForInit(
            stored, forceJioSaavnOff = true,
            legacyModuleIndexUrl = "https://example.invalid/index.json",
        )
        assertEquals(1, seeded.count { it.kind == SourceKind.CUSTOM_MODULE })
        assertEquals("mine", seeded.first { it.kind == SourceKind.CUSTOM_MODULE }.id)
    }

    @Test
    fun `a blank legacy key migrates nothing`() {
        val seeded = SourceRegistry.sourcesForInit(
            emptyList(), forceJioSaavnOff = true, legacyModuleIndexUrl = "   ",
        )
        assertTrue(seeded.none { it.kind == SourceKind.CUSTOM_MODULE })
    }

    @Test
    fun `a trailing slash is trimmed so the duplicate check can see it`() {
        val seeded = SourceRegistry.sourcesForInit(
            emptyList(), forceJioSaavnOff = true,
            legacyModuleIndexUrl = "  https://example.invalid/index.json/  ",
        )
        assertEquals(
            "https://example.invalid/index.json",
            seeded.first { it.kind == SourceKind.CUSTOM_MODULE }.baseUrl,
        )
    }
}
