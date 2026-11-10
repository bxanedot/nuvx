package com.bxane.nuvx

import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AddonOrderTest {

    private val before = SourceRegistry.configs.value

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
    }

    private fun addon(name: String) = SourceConfig(
        kind = SourceKind.ADDON,
        label = name,
        baseUrl = "https://$name.example.com",
    )

    private fun seed(vararg names: String): List<SourceConfig> {
        val addons = names.map(::addon)
        SourceRegistry.configs.value = addons +
            SourceConfig(kind = SourceKind.JIOSAAVN) +
            SourceConfig(kind = SourceKind.YOUTUBE)
        return addons
    }

    private fun addonLabels() =
        SourceRegistry.configs.value.filter { it.kind.isUserAdded }.map { it.label }

    @Test
    fun `reordering puts the addons in the order given`() {
        val (a, b, c) = seed("a", "b", "c").let { Triple(it[0], it[1], it[2]) }

        SourceRegistry.reorderAddons(listOf(c.id, a.id, b.id))

        assertEquals(listOf("c", "a", "b"), addonLabels())
    }

    @Test
    fun `reordering leaves the fixed-rank sources alone`() {
        val addons = seed("a", "b")

        SourceRegistry.reorderAddons(listOf(addons[1].id, addons[0].id))

        assertEquals(
            listOf(SourceKind.ADDON, SourceKind.ADDON, SourceKind.JIOSAAVN, SourceKind.YOUTUBE),
            SourceRegistry.configs.value.map { it.kind },
        )
    }

    @Test
    fun `an addon missing from the order is kept rather than dropped`() {
        val addons = seed("a", "b", "c")

        SourceRegistry.reorderAddons(listOf(addons[2].id, addons[0].id))

        assertEquals(listOf("c", "a", "b"), addonLabels())
    }

    @Test
    fun `an unknown id in the order is ignored`() {
        val addons = seed("a", "b")

        SourceRegistry.reorderAddons(listOf("not-an-id", addons[1].id, addons[0].id))

        assertEquals(listOf("b", "a"), addonLabels())
    }

    @Test
    fun `a single addon is left untouched`() {
        val addons = seed("only")

        SourceRegistry.reorderAddons(listOf(addons[0].id))

        assertEquals(listOf("only"), addonLabels())
    }

    @Test
    fun `an exact repeat of a configured URL is a duplicate`() {
        val addons = seed("a")
        assertEquals(addons[0].id, SourceRegistry.duplicateOf(addons[0].baseUrl)?.id)
    }

    @Test
    fun `a trailing slash does not make a new source`() {
        val addons = seed("a")
        assertEquals(addons[0].id, SourceRegistry.duplicateOf(addons[0].baseUrl + "/")?.id)
    }

    @Test
    fun `a differently-cased host is the same source`() {
        seed("a")
        assertNotNull(SourceRegistry.duplicateOf("https://A.EXAMPLE.COM"))
    }

    @Test
    fun `paths differing only in case are different sources`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://host.example.com/AbC"),
        )
        assertNull(SourceRegistry.duplicateOf("https://host.example.com/abc"))
        assertNotNull(SourceRegistry.duplicateOf("https://host.example.com/AbC"))
    }

    @Test
    fun `a source is not its own duplicate when it is the one being edited`() {
        val addons = seed("a")
        assertNull(SourceRegistry.duplicateOf(addons[0].baseUrl, exceptId = addons[0].id))
    }

    @Test
    fun `an unconfigured URL is not a duplicate`() {
        seed("a")
        assertNull(SourceRegistry.duplicateOf("https://b.example.com"))
    }

    @Test
    fun `a blank URL never matches the built-in sources`() {
        seed("a")
        assertNull(SourceRegistry.duplicateOf(""))
        assertNull(SourceRegistry.duplicateOf("   "))
    }
}
