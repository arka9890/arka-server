package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReleaseTest {

    @Test
    fun versionCore_extractsNumericParts() {
        assertEquals(listOf(1, 19, 1), EngineRelease.versionCore("v1.19.1"))
        assertEquals(listOf(1, 19, 1), EngineRelease.versionCore("1.19.1"))
        assertEquals(listOf(1, 19, 1), EngineRelease.versionCore("opencode 1.19.1"))
        assertEquals(listOf(1, 19), EngineRelease.versionCore("1.19"))
    }

    @Test
    fun isNewer_comparesCorrectly() {
        assertTrue(EngineRelease.isNewer("v1.19.1", "1.19.0"))
        assertTrue(EngineRelease.isNewer("v1.20.0", "1.19.9"))
        assertTrue(EngineRelease.isNewer("v2.0.0", "1.99.99"))
        assertFalse(EngineRelease.isNewer("v1.19.0", "1.19.0"))
        assertFalse(EngineRelease.isNewer("v1.19.0", "1.19.1"))
        // pad missing segments with zero: 1.19 == 1.19.0
        assertFalse(EngineRelease.isNewer("v1.19", "1.19.0"))
        assertTrue(EngineRelease.isNewer("v1.19.1", "1.19"))
    }

    @Test
    fun isNewer_nullInstalled_meansUpdate() {
        assertTrue(EngineRelease.isNewer("v1.19.1", null))
    }

    @Test
    fun sameVersion_ignoresFormatting() {
        assertTrue(EngineRelease.sameVersion("v1.19.1", "1.19.1"))
        assertFalse(EngineRelease.sameVersion("v1.19.1", "1.19.0"))
        assertFalse(EngineRelease.sameVersion("v1.19.1", "abc"))
    }

    @Test
    fun normalizeTag_addsPrefix() {
        assertEquals("v1.19.1", EngineRelease.normalizeTag("1.19.1"))
        assertEquals("v1.19.1", EngineRelease.normalizeTag("v1.19.1"))
        assertEquals("v1.19.1", EngineRelease.normalizeTag("  v1.19.1/ "))
    }

    @Test
    fun bare_stripsLeadingV() {
        assertEquals("1.19.1", EngineRelease.bare("v1.19.1"))
        assertEquals("1.19.1", EngineRelease.bare("1.19.1"))
        assertEquals("1.19.1", EngineRelease.bare("  v1.19.1 "))
        assertEquals("1.19.1", EngineRelease.bare("V1.19.1"))
        assertEquals("?", EngineRelease.bare("?"))
    }

    @Test
    fun packageCandidates_arm64MuslFirst() {
        val c = EngineRelease.packageCandidates("arm64-v8a")
        assertEquals("@opencode/cli-linux-arm64-musl", c.first())
        assertTrue(c.contains("@opencode/cli-linux-arm64"))
    }

    @Test
    fun packageCandidates_x64() {
        val c = EngineRelease.packageCandidates("x86_64")
        assertEquals("@opencode/cli-linux-x64-musl", c.first())
        assertTrue(c.contains("@opencode/cli-linux-x64"))
    }

    @Test
    fun downloadUrl_shape() {
        assertEquals(
            "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.31.tgz",
            EngineRelease.downloadUrl("v2.0.31", "@opencode/cli-linux-arm64-musl"),
        )
    }
}