package com.bxane.nuvx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
