package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Refactor-dpkg-world] Pure-JVM tests for the dpkg world snapshot pipeline:
 * parsing Ubuntu's `/var/lib/dpkg/status` on the host side, serializing to
 * the host snapshot file (`name=version` lines), and parsing it back.
 */
class DpkgWorldSnapshotTest {

    // ── parseDpkgStatus ─────────────────────────────────────────────────

    /** A realistic slice of Ubuntu 24.04 `/var/lib/dpkg/status`. */
    private val dpkgStatusSample = """
        Package: base-files
        Status: install ok installed
        Version: 13ubuntu10.4
        Section: admin
        Priority: required
        Description: Debian base system miscellaneous files

        Package: bash
        Essential: yes
        Status: install ok installed
        Version: 5.2.21-2ubuntu4
        Section: shells
        Priority: required
        Description: GNU Bourne Again SHell

        Package: libc6
        Status: install ok installed
        Version: 2.39-0ubuntu8.6
        Section: libs
        Priority: optional

        Package: zsh
        Status: deinstall ok config-files
        Version: 5.9-6build1
        Section: shells
        Priority: optional

        Package: python3-minimal
        Status: install ok half-configured
        Version: 3.12.3-0ubuntu2
        Section: python
    """.trimIndent()

    @Test
    fun `parse dpkg status into name-version pairs`() {
        val pkgs = parseDpkgStatus(dpkgStatusSample)
        assertEquals(listOf("base-files", "bash", "libc6", "python3-minimal"), pkgs.map { it.name })
        assertEquals(
            listOf("13ubuntu10.4", "5.2.21-2ubuntu4", "2.39-0ubuntu8.6", "3.12.3-0ubuntu2"),
            pkgs.map { it.version },
        )
    }

    @Test
    fun `epoch-qualified versions are preserved verbatim`() {
        val text = """
            Package: dash
            Status: install ok installed
            Version: 0.5.12-9ubuntu1

            Package: findutils
            Status: install ok installed
            Version: 2:8.4.0-1
        """.trimIndent()
        val pkgs = parseDpkgStatus(text)
        assertEquals(ApkPackage("findutils", "2:8.4.0-1"), pkgs.last())
    }

    @Test
    fun `not-installed and config-files blocks are skipped`() {
        // `deinstall ok config-files` = user removed it, only conffiles remain —
        // restoring it would resurrect a package the user deliberately removed.
        val text = """
            Package: removed-pkg
            Status: deinstall ok config-files
            Version: 1.0

            Package: kept-pkg
            Status: install ok installed
            Version: 2.0

            Package: purge-listed
            Status: purge ok not-installed
            Version: 3.0
        """.trimIndent()
        assertEquals(listOf(ApkPackage("kept-pkg", "2.0")), parseDpkgStatus(text))
    }

    @Test
    fun `parse empty status yields empty list`() {
        assertTrue(parseDpkgStatus("").isEmpty())
        assertTrue(parseDpkgStatus("\n\n  \n").isEmpty())
    }

    @Test
    fun `trailing block without blank line is captured`() {
        val text = """
            Package: zeta
            Status: install ok installed
            Version: 1.2.3
        """.trimIndent()
        assertEquals(listOf(ApkPackage("zeta", "1.2.3")), parseDpkgStatus(text))
    }

    @Test
    fun `block missing version is skipped`() {
        val text = """
            Package: no-version-here
            Status: install ok installed

            Package: ok
            Status: install ok installed
            Version: 2.0.0
        """.trimIndent()
        assertEquals(listOf(ApkPackage("ok", "2.0.0")), parseDpkgStatus(text))
    }

    @Test
    fun `block with version but no name is skipped`() {
        val text = """
            Version: 1.0.0
            Status: install ok installed

            Package: real
            Status: install ok installed
            Version: 4.4.4
        """.trimIndent()
        assertEquals(listOf(ApkPackage("real", "4.4.4")), parseDpkgStatus(text))
    }

    // ── formatDpkgWorld / parseDpkgWorld round-trip ────────────────────

    @Test
    fun `world format parse round-trips`() {
        val pkgs = listOf(
            ApkPackage("curl", "8.5.0-2ubuntu10"),
            ApkPackage("python3", "3.12.3-0ubuntu2"),
            ApkPackage("vim", "2:9.1.0016-1ubuntu7"),
        )
        val text = formatDpkgWorld(pkgs)
        assertEquals(pkgs, parseDpkgWorld(text))
    }

    @Test
    fun `parseDpkgWorld tolerates comments blanks and bad lines`() {
        val text = """
            # dpkg-world snapshot — <name>=<version> per line
            curl=8.5.0-2ubuntu10

            python3=3.12.3-0ubuntu2
            =broken-no-name
            nope-just-a-name
            git=   1:2.43.0-1ubuntu7  
        """.trimIndent()
        assertEquals(
            listOf(
                ApkPackage("curl", "8.5.0-2ubuntu10"),
                ApkPackage("python3", "3.12.3-0ubuntu2"),
                ApkPackage("git", "1:2.43.0-1ubuntu7"),
            ),
            parseDpkgWorld(text),
        )
    }

    @Test
    fun `parse empty world text yields empty list`() {
        assertTrue(parseDpkgWorld("").isEmpty())
        assertTrue(parseDpkgWorld("# only a comment\n").isEmpty())
    }
}
