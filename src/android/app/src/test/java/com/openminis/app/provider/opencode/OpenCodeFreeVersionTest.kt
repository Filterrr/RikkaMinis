package com.openminis.app.provider.opencode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-opencode-free] Pure-JVM tests for the free tier's version negotiation.
 *
 * These lock in the two facts the self-healing design rests on, both of which
 * were established by live probing (see `docs/opencode-free-tier.md`):
 *
 *  1. the upstream enforces a FLOOR, never an exact version, and has no upper
 *     bound — so adopting anything at or above the demanded floor is safe;
 *  2. a 426 body states the demanded floor in prose, which is the only signal
 *     available at the moment the request fails.
 */
class OpenCodeFreeVersionTest {

    @After
    fun tearDown() {
        OpenCodeFree.resetVersionStateForTest()
    }

    // ─── normalize / compare ───────────────────────────────────────────────

    @Test
    fun `normalize strips v prefix and prerelease suffix`() {
        assertEquals("1.20.0", OpenCodeFree.normalizeVersion("v1.20.0-beta.3"))
        assertEquals("1.20.0", OpenCodeFree.normalizeVersion("1.20.0+build.7"))
        assertEquals("1.19.3", OpenCodeFree.normalizeVersion("  1.19.3  "))
    }

    @Test
    fun `normalize rejects non-numeric identities`() {
        // The upstream's UA gate matches on a numeric version; "opencode",
        // "opencode/x.y.z" and "" are all FreeTierError, and we must never
        // adopt them as a version.
        assertNull(OpenCodeFree.normalizeVersion("opencode"))
        assertNull(OpenCodeFree.normalizeVersion("x.y.z"))
        assertNull(OpenCodeFree.normalizeVersion(""))
        assertNull(OpenCodeFree.normalizeVersion(null))
    }

    @Test
    fun `version comparison is numeric not lexicographic`() {
        assertTrue(OpenCodeFree.isVersionAtLeast("1.19.3", "1.18.0"))
        assertTrue(OpenCodeFree.isVersionAtLeast("1.18.0", "1.18.0"))
        // Lexicographic comparison would put "1.9.0" above "1.18.0".
        assertFalse(OpenCodeFree.isVersionAtLeast("1.9.0", "1.18.0"))
        assertTrue(OpenCodeFree.isVersionAtLeast("2.0", "1.18.0"))
        assertFalse(OpenCodeFree.isVersionAtLeast("garbage", "1.18.0"))
    }

    @Test
    fun `bumpPatch steps above the demanded floor`() {
        assertEquals("1.18.1", OpenCodeFree.bumpPatch("1.18.0"))
        assertEquals("2.0.1", OpenCodeFree.bumpPatch("2.0.0"))
    }

    // ─── 426 body parsing ──────────────────────────────────────────────────

    @Test
    fun `parseRequiredVersion reads the real 426 body`() {
        // Verbatim from the live endpoint.
        val body = """{"type":"error","error":{"type":"UpgradeRequired",""" +
            """"message":"OpenCode 1.18.0 or newer is required to use the free tier"}}"""
        assertEquals("1.18.0", OpenCodeFree.parseRequiredVersion(body))
    }

    @Test
    fun `parseRequiredVersion tolerates casing and returns null on garbage`() {
        assertEquals("2.3.4", OpenCodeFree.parseRequiredVersion("opencode 2.3.4 or newer is required"))
        assertNull(OpenCodeFree.parseRequiredVersion("""{"type":"error"}"""))
        assertNull(OpenCodeFree.parseRequiredVersion(""))
    }

    // ─── adoption / ordering ───────────────────────────────────────────────

    @Test
    fun `adoptRequiredVersion raises the effective version above the demand`() {
        val before = OpenCodeFree.currentVersion()
        assertTrue(OpenCodeFree.adoptRequiredVersion("OpenCode 9.0.0 or newer is required"))
        assertEquals("9.0.1", OpenCodeFree.currentVersion())
        assertTrue(OpenCodeFree.isVersionAtLeast(OpenCodeFree.currentVersion(), "9.0.0"))
        assertFalse(before == OpenCodeFree.currentVersion())
    }

    @Test
    fun `adoptRequiredVersion ignores a demand below the supported floor`() {
        // A bogus/rollback demand must not drag us below what we know works.
        assertFalse(OpenCodeFree.adoptRequiredVersion("OpenCode 0.0.1 or newer is required"))
        assertEquals(OpenCodeFree.DEFAULT_CLIENT_VERSION, OpenCodeFree.currentVersion())
    }

    @Test
    fun `adoption is monotonic - a lower demand never wins`() {
        assertTrue(OpenCodeFree.adoptRequiredVersion("OpenCode 9.0.0 or newer"))
        assertFalse(OpenCodeFree.adoptRequiredVersion("OpenCode 5.0.0 or newer"))
        assertEquals("9.0.1", OpenCodeFree.currentVersion())
    }

    @Test
    fun `advertised version is used when it beats the pin`() {
        assertTrue(OpenCodeFree.resolveAdvertisedVersion("1.19.3"))
        assertEquals("1.19.3", OpenCodeFree.currentVersion())
    }

    @Test
    fun `a server demand outranks the registry`() {
        OpenCodeFree.resolveAdvertisedVersion("1.19.3")
        OpenCodeFree.adoptRequiredVersion("OpenCode 9.0.0 or newer")
        // The server just told us the pin/registry value is insufficient.
        assertEquals("9.0.1", OpenCodeFree.currentVersion())
    }

    @Test
    fun `registry below the floor is not adopted`() {
        assertFalse(OpenCodeFree.resolveAdvertisedVersion("1.0.0"))
        assertEquals(OpenCodeFree.DEFAULT_CLIENT_VERSION, OpenCodeFree.currentVersion())
    }

    // ─── UA shape the gate actually accepts ────────────────────────────────

    @Test
    fun `userAgent always carries a numeric version and the opencode prefix`() {
        val ua = OpenCodeFree.userAgent()
        assertTrue("UA must start with opencode/: $ua", ua.startsWith("opencode/"))
        assertTrue("UA must end with the client segment: $ua", ua.endsWith("/cli"))
        // The exact shapes that were observed to pass the gate.
        val version = ua.removePrefix("opencode/prod/").removeSuffix("/cli")
        assertTrue("UA version must be numeric: $version", OpenCodeFree.normalizeVersion(version) != null)
    }

    @Test
    fun `pin is above the observed floor`() {
        assertTrue(
            OpenCodeFree.isVersionAtLeast(
                OpenCodeFree.DEFAULT_CLIENT_VERSION,
                OpenCodeFree.MIN_SUPPORTED_CLIENT_VERSION,
            ),
        )
    }
}
