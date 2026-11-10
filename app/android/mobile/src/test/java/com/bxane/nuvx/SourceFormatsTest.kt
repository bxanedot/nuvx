package com.bxane.nuvx

import com.bxane.nuvx.data.sources.addon.DetectedFormat
import com.bxane.nuvx.data.sources.addon.SourceFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFormatsTest {

    private fun reason(format: DetectedFormat) = (format as DetectedFormat.Unsupported).reason

    @Test
    fun `a real addon manifest is recognised and its name read`() {
        val body = """
            {"id":"com.unified.music.quality","name":"Unified · Quality First","version":"1.0.0",
             "description":"Tidal + Qobuz + YouTube Music + JioSaavn in one addon.",
             "icon":"https://unified-addon.netlify.app/icon.png",
             "resources":["search","stream","catalog"],
             "types":["track","album","artist","playlist"],"contentType":"music"}
        """.trimIndent()

        val found = SourceFormats.detect(body, "https://host/tok/quality/manifest.json")

        assertTrue("expected Addon, got $found", found is DetectedFormat.Addon)
        found as DetectedFormat.Addon
        assertEquals("Unified · Quality First", found.manifest.displayName)
        assertEquals("https://host/tok/quality", found.baseUrl)
    }

    @Test
    fun `a manifest served as some other json filename still resolves its base`() {
        val body = """{"id":"x","name":"X","resources":["search","stream"]}"""

        val found = SourceFormats.detect(body, "https://host/addon.json") as DetectedFormat.Addon

        assertEquals("https://host", found.baseUrl)
    }

    @Test
    fun `a module index is recognised by its category keys`() {
        val body = """
            {"category:music":[
              {"id":"m1","name":"One","download":"one.js"},
              {"id":"m2","name":"Two","download":"two.js"}]}
        """.trimIndent()

        val found = SourceFormats.detect(body, "https://host/index.json")

        assertTrue("expected ModuleIndex, got $found", found is DetectedFormat.ModuleIndex)
        found as DetectedFormat.ModuleIndex
        assertEquals(2, found.moduleCount)
        assertEquals("https://host/index.json", found.url)
    }

    @Test
    fun `an addon that declares search but not stream is accepted`() {
        val body = """{"id":"x","name":"X","resources":["search","catalog"]}"""

        assertTrue(SourceFormats.detect(body) is DetectedFormat.Addon)
    }

    @Test
    fun `an addon that cannot be searched is refused and says what it declared`() {
        val body = """{"id":"x","name":"X","resources":["stream","catalog"]}"""

        val reason = reason(SourceFormats.detect(body))

        assertTrue(reason, reason.contains("stream, catalog"))
        assertTrue(reason, reason.contains("needs search"))
    }

    @Test
    fun `a manifest declaring no resources is still an addon`() {
        val body = """{"id":"x","name":"X","version":"1.0.0"}"""

        assertTrue(SourceFormats.detect(body) is DetectedFormat.Addon)
    }


    @Test
    fun `a module index listing nothing is refused`() {
        val reason = reason(SourceFormats.detect("""{"category:music":[]}"""))
        assertTrue(reason, reason.contains("listed no modules"))
    }

    @Test
    fun `a page that is not JSON says so`() {
        val reason = reason(SourceFormats.detect("<!doctype html><html><body>Not found</body></html>"))
        assertTrue(reason, reason.contains("did not return JSON"))
    }

    @Test
    fun `a bare JSON array is refused`() {
        val reason = reason(SourceFormats.detect("""[{"id":"a"}]"""))
        assertTrue(reason, reason.contains("list"))
    }

    @Test
    fun `an unknown index names the list it holds`() {
        val reason = reason(SourceFormats.detect("""{"schemaVersion":2,"providers":[{"id":"a"},{"id":"b"}]}"""))
        assertTrue(reason, reason.contains("providers"))
    }

    @Test
    fun `an extension registry falls to the generic unrecognised answer`() {
        val body = """
            {"version":1,"updated_at":"2026-08-30T00:00:00Z","extensions":[
              {"id":"deezer","display_name":"Deezer","download_url":"https://x/deezer.sflx"},
              {"id":"qobuz-web","display_name":"Qobuz","download_url":"https://x/qobuz-web.sflx"}]}
        """.trimIndent()

        val reason = reason(SourceFormats.detect(body, "https://host/registry.json"))

        assertTrue(reason, reason.contains("extensions"))
        assertTrue(reason, reason.contains("not a format nuvx reads"))
    }

    @Test
    fun `an object with nothing in it lists what it did hold`() {
        val reason = reason(SourceFormats.detect("""{"hello":"world","count":3}"""))
        assertTrue(reason, reason.contains("hello"))
    }
}
