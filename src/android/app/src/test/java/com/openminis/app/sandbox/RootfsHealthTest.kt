package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-rootfs-integrity] Pure-JVM tests for [RootfsHealth]'s derived state.
 * Pins down the contract the boot path and the Repair Terminal button rely
 * on: `healthy` ⇔ sandbox can function, `terminalOk` ⇔ interactive bash can
 * start, `missing` is an accurate diff.
 */
class RootfsHealthTest {

    private fun healthy() = RootfsHealth(
        bash = true, sh = true, libc = true,
        glibc = true, aptGet = true, dpkgDatabase = true,
    )

    @Test
    fun `all present is healthy and terminal-ready`() {
        val h = healthy()
        assertTrue(h.healthy)
        assertTrue(h.terminalOk)
        assertTrue(h.missing.isEmpty())
    }

    @Test
    fun `missing glibc breaks terminal and sandbox`() {
        val h = healthy().copy(glibc = false)
        // bash needs libc.so.6 — nothing works without it.
        assertFalse(h.healthy)
        assertFalse(h.terminalOk)
        assertEquals(listOf("/lib/aarch64-linux-gnu/libc.so.6"), h.missing)
    }

    @Test
    fun `missing bash breaks both terminal and sandbox-health`() {
        val h = healthy().copy(bash = false)
        assertFalse(h.healthy)
        assertFalse(h.terminalOk)
        assertEquals(listOf("/bin/bash"), h.missing)
    }

    @Test
    fun `missing apt-get breaks sandbox health`() {
        val h = healthy().copy(aptGet = false)
        assertFalse(h.healthy)
        assertTrue(h.terminalOk) // bash can still run
        assertEquals(listOf("/usr/bin/apt-get"), h.missing)
    }

    @Test
    fun `multiple missing aggregate in canonical order`() {
        val h = RootfsHealth(
            bash = false, sh = false, libc = true,
            glibc = false, aptGet = false, dpkgDatabase = true,
        )
        assertFalse(h.healthy)
        assertFalse(h.terminalOk)
        assertEquals(
            listOf("/usr/bin/bash", "/bin/sh", "/lib/aarch64-linux-gnu/libc.so.6", "/usr/bin/apt-get"),
            h.missing,
        )
    }

    // ── Integrity manifest parsing tests ──────────────────────────────

    @Test
    fun `parse empty manifest yields empty map`() {
        assertTrue(RootfsManager.parseIntegrityManifest("").isEmpty())
        assertTrue(RootfsManager.parseIntegrityManifest("   \n\n  ").isEmpty())
    }

    @Test
    fun `parse valid manifest lines`() {
        val manifest = """
            usr/bin/bash=1234567
            bin/sh=890123
            lib/ld-linux-aarch64.so.1=456789
        """.trimIndent()
        val map = RootfsManager.parseIntegrityManifest(manifest)
        assertEquals(3, map.size)
        assertEquals(1234567L, map["usr/bin/bash"])
        assertEquals(890123L, map["bin/sh"])
        assertEquals(456789L, map["lib/ld-linux-aarch64.so.1"])
    }

    @Test
    fun `parse manifest skips malformed lines`() {
        val manifest = """
            bin/bash=1234567
            no-equals-here
            =nokey
            libc=badvalue
            bin/sh=890123
        """.trimIndent()
        val map = RootfsManager.parseIntegrityManifest(manifest)
        assertEquals(2, map.size)
        assertEquals(1234567L, map["usr/bin/bash"])
        assertEquals(890123L, map["bin/sh"])
    }

    @Test
    fun `parse manifest with trailing newline and whitespace`() {
        val manifest = "  usr/bin/bash=100  \n  bin/sh=200  \n"
        val map = RootfsManager.parseIntegrityManifest(manifest)
        assertEquals(2, map.size)
assertEquals(100L, map["usr/bin/bash"])
        assertEquals(200L, map["bin/sh"])
    }

    // ── Integrity size-check contract ─────────────────────────────────

    @Test
    fun `bin-sh is dpkg-managed so dash upgrade does not fail integrity`() {
        // /bin/sh -> /usr/bin/dash symlink: dash's size changes with apt
        // upgrades, so the manifest's factory size must NOT be asserted.
        // Regression class inherited from the Alpine build: every boot
        // reported missing=[/bin/sh] after any shell binary change.
        assertTrue("bin/sh" in DYNAMIC_INTEGRITY_PATHS)
        val manifest = mapOf("bin/sh" to 890123L)
        assertTrue(integritySizePasses("bin/sh", actualSize = 999_999L, expectedSizes = manifest))
    }

    @Test
    fun `all dynamic paths are existence-only regardless of manifest size`() {
        val manifest = mapOf(
            "usr/bin/bash" to 1234567L,
            "lib/aarch64-linux-gnu/libc.so.6" to 42L,
            "var/lib/dpkg/status" to 14907L,
        )
        for (rel in listOf(
            "usr/bin/bash",
            "lib/aarch64-linux-gnu/libc.so.6",
            "var/lib/dpkg/status",
        )) {
            assertTrue("$rel should be dynamic", rel in DYNAMIC_INTEGRITY_PATHS)
            assertTrue(integritySizePasses(rel, actualSize = Long.MAX_VALUE, expectedSizes = manifest))
        }
    }

    @Test
    fun `static files still assert factory snapshot size`() {
        assertFalse("lib/ld-linux-aarch64.so.1" in DYNAMIC_INTEGRITY_PATHS)
        assertFalse("usr/bin/apt-get" in DYNAMIC_INTEGRITY_PATHS)
        val manifest = mapOf("lib/ld-linux-aarch64.so.1" to 456789L)
        // Truncated static file (size mismatch) must still be caught…
        assertFalse(integritySizePasses("lib/ld-linux-aarch64.so.1", actualSize = 111L, expectedSizes = manifest))
        // …and an intact one passes.
        assertTrue(integritySizePasses("lib/ld-linux-aarch64.so.1", actualSize = 456789L, expectedSizes = manifest))
    }

    @Test
    fun `missing manifest entry falls back to existence-only`() {
        assertTrue(integritySizePasses("usr/bin/apt-get", actualSize = 0L, expectedSizes = emptyMap()))
    }

    @Test
    fun `missing sh is not accepted as healthy`() {
        val h = healthy().copy(sh = false)
        assertFalse(h.healthy)
        assertEquals(listOf("/bin/sh"), h.missing)
    }

    @Test
    fun `offline repair health requires sh`() {
        val h = healthy().copy(sh = false)
        // Mirrors autoRepair's Stage 2 success gate.
        assertFalse(h.healthy)
        assertTrue(h.bash && h.glibc)
    }

    @Test
    fun `rebuilds missing sh as dash symlink`() {
        val root = java.nio.file.Files.createTempDirectory("rootfs-sh-").toFile()
        try {
            val bin = java.io.File(root, "bin").apply { mkdirs() }
            val usrBin = java.io.File(root, "usr/bin").apply { mkdirs() }
            java.io.File(usrBin, "dash").writeText("dash")
            assertTrue(ensureDashShellSymlink(root))
            val shell = java.io.File(bin, "sh").toPath()
            assertTrue(java.nio.file.Files.isSymbolicLink(shell))
            assertEquals("/usr/bin/dash", java.nio.file.Files.readSymbolicLink(shell).toString())
        } finally {
            root.deleteRecursively()
        }
    }

}
