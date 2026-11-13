package com.bxane.nuvx.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue










class AppUpdateCheckerTest {

    private val json = Json { ignoreUnknownKeys = true }


    private fun kotlinx.serialization.json.JsonElement.releaseInfo() =
        with(AppUpdateChecker) { releaseInfo() }

    @Test
    fun `a higher version is newer`() {
        assertTrue(AppUpdateChecker.isNewer(latest = "1.0.1", current = "1.0.0"))
        assertTrue(AppUpdateChecker.isNewer(latest = "1.1.0", current = "1.0.9"))
        assertTrue(AppUpdateChecker.isNewer(latest = "2.0.0", current = "1.9.9"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppUpdateChecker.isNewer(latest = "1.0.0", current = "1.0.0"))
    }

    @Test
    fun `a lower version is not newer`() {
        assertFalse(AppUpdateChecker.isNewer(latest = "1.0.0", current = "1.0.1"))
        assertFalse(AppUpdateChecker.isNewer(latest = "0.9.9", current = "1.0.0"))
    }

    @Test
    fun `numbers are compared as numbers and not as text`() {


        assertTrue(AppUpdateChecker.isNewer(latest = "1.0.10", current = "1.0.9"))
        assertTrue(AppUpdateChecker.isNewer(latest = "1.10.0", current = "1.9.0"))
    }

    @Test
    fun `a missing part counts as zero`() {

        assertFalse(AppUpdateChecker.isNewer(latest = "1.1", current = "1.1.0"))
        assertFalse(AppUpdateChecker.isNewer(latest = "1.1.0", current = "1.1"))
        assertTrue(AppUpdateChecker.isNewer(latest = "1.2", current = "1.1.9"))
    }

    @Test
    fun `a v prefix is not part of the version`() {


        assertFalse(AppUpdateChecker.isNewer(latest = "v1.2.3", current = "1.2.3"))
        assertTrue(AppUpdateChecker.isNewer(latest = "v1.2.4", current = "1.2.3"))
        assertTrue(AppUpdateChecker.isNewer(latest = "V1.2.4", current = "1.2.3"))
    }

    @Test
    fun `a prerelease does not supersede the release it leads up to`() {



        assertFalse(AppUpdateChecker.isNewer(latest = "2.0.0-rc1", current = "1.9.0"))


        assertFalse(AppUpdateChecker.isNewer(latest = "1.9.0", current = "2.0.0-rc1"))


        assertTrue(AppUpdateChecker.isNewer(latest = "1.9.0", current = "1.9.0-rc1"))
    }

    @Test
    fun `two prereleases of the same numbers are the same version here`() {




        assertFalse(AppUpdateChecker.isNewer(latest = "2.0.0-rc2", current = "2.0.0-rc1"))
        assertFalse(AppUpdateChecker.isNewer(latest = "2.0.0-rc1", current = "2.0.0-rc2"))
    }

    @Test
    fun `a build suffix is not a prerelease`() {

        assertFalse(AppUpdateChecker.isNewer(latest = "1.2.3+build.7", current = "1.2.3"))
    }

    @Test
    fun `a tag with no numbers is version zero rather than a refusal`() {


        assertFalse(AppUpdateChecker.isNewer(latest = "nightly", current = "0.0.1"))
        assertTrue(AppUpdateChecker.isNewer(latest = "0.0.1", current = "nightly"))
    }

    @Test
    fun `a part with a word in it is not a number at all`() {



        assertEquals(listOf(1, 2, 0), AppUpdateChecker.parseVersion("1.2.3beta").parts)
        assertEquals(listOf(1, 2, 3), AppUpdateChecker.parseVersion("1.2.3").parts)
    }



    @Test
    fun `a release object is read for its tag its page and its notes`() {
        val info = release(
            tag = "v2.1.0",
            url = "https://github.com/bxanedot/nuvx/releases/tag/v2.1.0",
            body = "## What's new\n- WebDAV\n",
        )
        assertEquals("2.1.0", info?.version)
        assertEquals("https://github.com/bxanedot/nuvx/releases/tag/v2.1.0", info?.releaseUrl)
        assertEquals("## What's new\n- WebDAV", info?.notes)
    }

    @Test
    fun `a release with no notes is still a release`() {
        val info = release(tag = "v2.1.0", url = "https://example.com/r", body = "   ")
        assertEquals("2.1.0", info?.version)
        assertNull(info?.notes, "blank notes are no notes, and an empty string is not a sentence")
    }

    @Test
    fun `a release with no link is nothing to show`() {

        assertNull(release(tag = "v2.1.0", url = null, body = "notes"))
        assertNull(release(tag = null, url = "https://example.com/r", body = "notes"))
    }

    @Test
    fun `a body that is not a release object is nothing to show`() {


        assertNull(json.parseToJsonElement("""{"message":"API rate limit exceeded"}""").releaseInfo())
        assertNull(json.parseToJsonElement("[]").releaseInfo())
        assertNull(json.parseToJsonElement("\"not an object\"").releaseInfo())
    }


    private fun release(tag: String?, url: String?, body: String?): AppUpdateChecker.UpdateInfo? {
        val fields = buildList {
            tag?.let { add("\"tag_name\": ${quote(it)}") }
            url?.let { add("\"html_url\": ${quote(it)}") }
            body?.let { add("\"body\": ${quote(it)}") }
            add("\"draft\": false")
            add("\"prerelease\": false")
        }
        return json.parseToJsonElement("{${fields.joinToString(",")}}").releaseInfo()
    }

    private fun quote(value: String) = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""
}
