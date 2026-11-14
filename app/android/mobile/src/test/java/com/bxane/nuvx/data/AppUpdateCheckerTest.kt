package com.bxane.nuvx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
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
    fun `stable builds can update to a newer nightly`() {
        assertTrue(
            isNewerRelease("0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T12:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T11:00:00Z"),
        )
    }

    @Test
    fun `nightly builds can update to a newer stable release`() {
        assertTrue(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-13T13:00:00Z", "0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T12:00:00Z"),
        )
    }

    @Test
    fun `nightly builds can return to an already published stable release`() {
        assertTrue(
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
    fun `older releases and the installed release are not offered`() {
        assertFalse(
            isNewerRelease("0.9.0-nightly.42.1", "nightly.42.1", "2026-11-13T12:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T13:00:00Z"),
        )
        assertFalse(
            isNewerRelease("0.9.0", "v0.9.0", "2026-11-13T13:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T12:00:00Z"),
        )
    }

    @Test
    fun `higher version wins even when its publication time is older`() {
        assertTrue(
            isNewerRelease("0.10.0-nightly.1.1", "nightly.1.1", "2026-11-13T11:00:00Z", "0.9.0", "v0.9.0", "2026-11-13T12:00:00Z"),
        )
    }
}
