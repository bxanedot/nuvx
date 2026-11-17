package com.licht.nuvx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `nightly version follows the app-specific APK asset`() {
        assertEquals(
            "0.9.0-nightly.180.1",
            versionFromApkAsset("NuvxAuto-v0.9.0-nightly.180.1-universal.apk", "NuvxAuto-v"),
        )
        assertEquals(
            "1.0.0-nightly.207.1",
            versionFromApkAsset("NuvxAuto-v1.0.0-nightly.207.1.apk", "NuvxAuto-v"),
        )
        assertNull(versionFromApkAsset("NuvxTV-v0.9.0-universal.apk", "NuvxAuto-v"))
    }

    @Test
    fun `single package builds select the only shared APK over legacy aliases`() {
        val assets = listOf(
            "nuvx-v1.0.0-gms" to "https://example.test/gms.apk",
            "nuvx-v1.0.0-foss" to "https://example.test/foss.apk",
            "nuvx-v1.0.0-universal" to "https://example.test/universal.apk",
        )

        assertEquals(
            "https://example.test/universal.apk",
            selectApkAssetUrl(assets, "single", listOf("arm64-v8a")),
        )
    }

    @Test
    fun `single package builds select plain APK names`() {
        val assets = listOf(
            "nuvx-v1.0.0-gms" to "https://example.test/gms.apk",
            "nuvx-v1.0.0-foss" to "https://example.test/foss.apk",
            "nuvx-v1.0.0" to "https://example.test/nuvx.apk",
        )

        assertEquals(
            "https://example.test/nuvx.apk",
            selectApkAssetUrl(assets, "single", listOf("arm64-v8a")),
        )
    }

    @Test
    fun `selects the installed distribution before legacy ABI assets`() {
        val assets = listOf(
            "nuvx-v1.0.0-arm64-v8a" to "https://example.test/arm64.apk",
            "nuvx-v1.0.0-gms" to "https://example.test/gms.apk",
            "nuvx-v1.0.0-foss" to "https://example.test/foss.apk",
        )

        assertEquals(
            "https://example.test/foss.apk",
            selectApkAssetUrl(assets, "foss", listOf("arm64-v8a")),
        )
        assertEquals(
            "https://example.test/gms.apk",
            selectApkAssetUrl(assets, "gms", listOf("arm64-v8a")),
        )
    }

    @Test
    fun `FOSS does not fall back to GMS or legacy APKs`() {
        val assets = listOf(
            "nuvx-v1.0.0-arm64-v8a" to "https://example.test/arm64.apk",
            "nuvx-v1.0.0-gms" to "https://example.test/gms.apk",
        )

        assertNull(selectApkAssetUrl(assets, "foss", listOf("arm64-v8a")))
    }

    @Test
    fun `GMS falls back to the matching legacy ABI asset`() {
        val assets = listOf(
            "nuvx-v1.0.0-x86_64" to "https://example.test/x86.apk",
            "nuvx-v1.0.0-arm64-v8a" to "https://example.test/arm64.apk",
        )

        assertEquals(
            "https://example.test/arm64.apk",
            selectApkAssetUrl(assets, "gms", listOf("arm64-v8a", "x86_64")),
        )
    }

    @Test
    fun `GMS falls back to a legacy universal asset`() {
        val assets = listOf("nuvx-v1.0.0" to "https://example.test/universal.apk")

        assertEquals(
            "https://example.test/universal.apk",
            selectApkAssetUrl(assets, "gms", listOf("arm64-v8a")),
        )
    }

    @Test
    fun `stable builds do not update to a nightly`() {
        assertFalse(
            isNewerRelease("1.0.0-nightly.42.1", "nightly.42.1", "2026-11-13T12:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T11:00:00Z"),
        )
    }

    @Test
    fun `nightly builds can update to the next stable version regardless of publish time`() {
        assertTrue(
            isNewerRelease("1.0.0", "v1.0.0", "2026-11-13T11:00:00Z", "0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T13:00:00Z"),
        )
    }

    @Test
    fun `nightly builds never return to the stable release of the same version`() {
        assertFalse(
            isNewerRelease("1.0.0", "v1.0.0", "2026-11-15T13:00:00Z", "1.0.0-nightly.1.1", "nightly.1.1", "2026-11-15T12:00:00Z"),
        )
    }

    @Test
    fun `nightly with no build time does not fall back to a same version stable`() {
        assertFalse(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-15T13:00:00Z", "0.9.0-nightly.180.1", "nightly.180.1", ""),
        )
    }

    @Test
    fun `nightly builds do not return to the stable release they came from`() {
        assertFalse(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-13T11:00:00Z", "0.9.0-nightly.43.1", "nightly.43.1", "2026-11-13T13:00:00Z"),
        )
    }

    @Test
    fun `nightly builds can update to a newer nightly`() {
        assertTrue(
            isNewerRelease("0.9.0-nightly.43.1", "nightly.43.1", "2026-11-13T13:00:00Z", "0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T12:00:00Z"),
        )
    }

    @Test
    fun `the next major nightly advances from an installed nightly`() {
        assertTrue(
            isNewerRelease("1.0.0-nightly.1.1", "nightly.1.1", "2026-11-14T13:00:00Z", "0.9.0-nightly.167.1", "nightly.167.1", "2026-11-14T12:00:00Z"),
        )
    }

    @Test
    fun `the older nightly base cannot replace a newer installed nightly`() {
        assertFalse(
            isNewerRelease("0.9.0-nightly.180.1", "nightly.180.1", "2026-11-15T13:00:00Z", "1.0.0-nightly.169.1", "nightly.169.1", "2026-11-15T12:00:00Z"),
        )
    }

    @Test
    fun `the previous stable release cannot replace a next major nightly`() {
        assertFalse(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-14T13:00:00Z", "1.0.0-nightly.1.1", "nightly.1.1", "2026-11-14T12:00:00Z"),
        )
    }

    @Test
    fun `older releases and the installed release are not offered`() {
        assertFalse(
            isNewerRelease("0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T13:00:00Z", "0.9.0-nightly.43.1", "nightly.43.1", "2026-11-13T12:00:00Z"),
        )
        assertFalse(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-13T13:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T12:00:00Z"),
        )
    }

    @Test
    fun `higher version wins even when its publication time is older`() {
        assertTrue(
            isNewerRelease("0.10.0", "v0.10.0", "2026-11-13T11:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T12:00:00Z"),
        )
    }

    @Test
    fun `nightlies are offered only when nightly updates are switched on`() {
        val built = "2026-11-17T10:00:00Z"
        val published = "2026-11-17T12:00:00Z"
        assertFalse(isNewerRelease("1.0.0-nightly.5.1", "nightly.5.1", published, "1.0.0", "v1.0.0", built, allowNightly = false))
        assertTrue(isNewerRelease("1.0.0-nightly.5.1", "nightly.5.1", published, "1.0.0", "v1.0.0", built, allowNightly = true))
        assertFalse(isNewerRelease("1.0.0-nightly.6.1", "nightly.6.1", published, "1.0.0-nightly.5.1", "nightly.5.1", built, allowNightly = false))
        assertTrue(isNewerRelease("1.0.0-nightly.6.1", "nightly.6.1", published, "1.0.0-nightly.5.1", "nightly.5.1", built, allowNightly = true))
    }

    @Test
    fun `a nightly install with nightlies off still moves to the stable build`() {
        val built = "2026-11-17T10:00:00Z"
        val published = "2026-11-17T12:00:00Z"
        assertTrue(isNewerRelease("1.0.0", "v1.0.0", published, "1.0.0-nightly.5.1", "nightly.5.1", built, allowNightly = false))
        assertFalse(isNewerRelease("1.0.0", "v1.0.0", published, "1.0.0-nightly.5.1", "nightly.5.1", built, allowNightly = true))
        assertFalse(isNewerRelease("1.0.0", "v1.0.0", "2026-11-17T09:00:00Z", "1.0.0-nightly.5.1", "nightly.5.1", built, allowNightly = false))
    }

    @Test
    fun `old builds and non-nightly pre-releases are never offered`() {
        assertTrue(isOldBuild("nuvx 1.1.0 (old build)", "v1.1.0", prerelease = true))
        assertTrue(isOldBuild("Old", "v0.9.0", prerelease = false))
        assertTrue(isOldBuild("nuvx 1.1.0-nightly.170.1 (old build)", "nightly.170.1", prerelease = true))
        assertTrue(isOldBuild("nuvx 1.2.0", "v1.2.0", prerelease = true))
        assertFalse(isOldBuild("nuvx 1.0.0", "v1.0.0", prerelease = false))
        assertFalse(isOldBuild("nuvx 1.0.0-nightly.7.1", "nightly.7.1", prerelease = true))
        assertFalse(isOldBuild("Golden hour", "v1.0.1", prerelease = false))
    }

    private fun release(version: String, tag: String, published: String) =
        AppUpdateChecker.ReleaseCandidate(version, tag, published, "https://example.invalid/$tag", "https://example.invalid/$tag.apk", null)

    private val stable100 = release("1.0.0", "v1.0.0", "2026-11-10T00:00:00Z")
    private val nightly121 = release("1.0.0-nightly.121.1", "nightly.121.1", "2026-11-11T00:00:00Z")
    private val nightly122 = release("1.0.0-nightly.122.1", "nightly.122.1", "2026-11-12T00:00:00Z")
    private val stable101 = release("1.0.1", "v1.0.1", "2026-11-13T00:00:00Z")
    private val stable120 = release("1.2.0", "v1.2.0", "2026-11-14T00:00:00Z")

    private fun offered(feed: List<AppUpdateChecker.ReleaseCandidate>, installed: AppUpdateChecker.ReleaseCandidate, nightlies: Boolean) =
        selectUpdate(feed, installed.version, installed.tag, installed.publishedAt, nightlies)?.tag

    @Test
    fun `with nightly updates on, stable moves to nightly 121 then nightly 122`() {
        assertEquals("nightly.121.1", offered(listOf(stable100, nightly121), stable100, nightlies = true))
        assertEquals("nightly.122.1", offered(listOf(stable100, nightly121, nightly122), nightly121, nightlies = true))
        assertNull(offered(listOf(stable100, nightly121, nightly122), nightly122, nightlies = true))
    }

    @Test
    fun `with nightly updates off, nightlies are never offered`() {
        val feed = listOf(stable100, nightly121, nightly122)
        assertNull(offered(feed, stable100, nightlies = false))
        assertNull(offered(feed, nightly121, nightlies = false))
    }

    @Test
    fun `a new stable release replaces the nightlies whatever the setting`() {
        val feed = listOf(stable100, nightly121, nightly122, stable101, stable120)
        for (nightlies in listOf(true, false)) {
            assertEquals("v1.2.0", offered(feed, nightly122, nightlies))
            assertEquals("v1.2.0", offered(feed, stable100, nightlies))
        }
        assertEquals("v1.0.1", offered(listOf(stable100, nightly121, nightly122, stable101), stable100, nightlies = true))
    }

    @Test
    fun `a stable release is chosen over a newer nightly of an older version`() {
        assertEquals("v1.0.1", offered(listOf(nightly122, stable101), stable100, nightlies = true))
    }
}
