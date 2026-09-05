package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * JVM tests for [extractTar]'s targeted-restore filtering (onlyPrefixes).
 * Covers: prefix filtering, stream alignment when entries are skipped,
 * multi-entry prefix matches (symlink + versioned target), exec-bit
 * preservation, symlink materialization, and the no-filter regression case.
 */
class RootfsTarExtractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarEntry(
        name: String,
        content: ByteArray = ByteArray(0),
        typeflag: Char = '0',
        mode: Int = 0b111_101_101, // 0755
        linkName: String = "",
    ): ByteArray {
        val header = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val bytes = s.toByteArray(StandardCharsets.US_ASCII)
            bytes.copyInto(header, off, 0, minOf(len, bytes.size))
        }
        put(name, 0, 100)
        put(mode.toString(8).padStart(6, '0') + "\u0000", 100, 8)
        put("000000\u0000", 108, 8)
        put("000000\u0000", 116, 8)
        put(content.size.toString(8).padStart(11, '0') + "\u0000", 124, 12)
        put("00000000000\u0000", 136, 12)
        header[156] = typeflag.code.toByte()
        put(linkName, 157, 100)
        put("ustar\u0000", 257, 6)
        put("00", 263, 2)
        // Checksum: sum of the header with the checksum field as spaces.
        header.fill(0x20.toByte(), 148, 156)
        val sum = header.sumOf { it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return header + content + ByteArray(padding(content.size))
    }

    private fun padding(size: Int): Int = if (size % 512 == 0) 0 else 512 - (size % 512)

    private fun extract(entries: List<ByteArray>, prefixes: Set<String>? = null) {
        val bytes = entries.fold(ByteArray(0)) { acc, e -> acc + e } + ByteArray(1024)
        extractTar(ByteArrayInputStream(bytes), tmp.root, prefixes)
    }

    // --- helpers ---------------------------------------------------------

    private fun assertFile(path: String, expectedContent: String) {
        val f = tmp.root.resolve(path)
        assertTrue("missing $path", f.isFile)
        assertEquals(expectedContent, f.readText())
    }

    // --- tests -----------------------------------------------------------

    @Test
    fun `onlyPrefixes extracts matching entries and skips the rest`() {
        val big = ByteArray(4096) { it.toByte() }
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("usr/share/ignored", big), // data + padding must be consumed
                tarEntry("bin/sh", "x".toByteArray()),
            ),
            prefixes = setOf("bin/"),
        )
        assertFile("bin/bash", "#!/bin/bash\n")
        assertFile("bin/sh", "x")
        assertFalse("usr/share/ignored must be filtered out", tmp.root.resolve("usr/share/ignored").exists())
    }

    @Test
    fun `filtered entry data is fully consumed so later entries parse`() {
        val big = ByteArray(3000) { 'A'.code.toByte() } // non-512-multiple: exercises padding skip
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("usr/share/big", big),
                tarEntry("etc/after", "after".toByteArray()),
            ),
            prefixes = setOf("bin/"),
        )
        // The skipped 3000-byte entry must not corrupt parsing of the last entry.
        assertFalse(tmp.root.resolve("etc/after").exists())
        assertFile("bin/bash", "#!/bin/bash\n")
    }

    @Test
    fun `prefix matches symlink chain together`() {
        // Ubuntu shape: /lib -> usr/lib, ld-linux symlink chain lives under
        // usr/lib/aarch64-linux-gnu/. Both link + target must be restored.
        extract(
            listOf(
                tarEntry("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "ELF-LOADER".toByteArray()),
                tarEntry(
                    "lib/ld-linux-aarch64.so.1",
                    typeflag = '2',
                    linkName = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                ),
            ),
            prefixes = setOf("lib/ld-linux-", "usr/lib/aarch64-linux-gnu/"),
        )
        val link = tmp.root.resolve("lib/ld-linux-aarch64.so.1")
        assertTrue("symlink must exist", Files.isSymbolicLink(link.toPath()))
        assertEquals("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", Files.readSymbolicLink(link.toPath()).toString())
        assertFile("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "ELF-LOADER")
    }

    @Test
    fun `symlink target restored when both are under the prefix`() {
        extract(
            listOf(
                tarEntry("usr/bin/dash", "DASH".toByteArray()),
                tarEntry("bin/sh", typeflag = '2', linkName = "usr/bin/dash"),
            ),
            prefixes = setOf("bin/", "usr/bin/dash"),
        )
        assertTrue(Files.isSymbolicLink(tmp.root.resolve("bin/sh").toPath()))
        assertFile("usr/bin/dash", "DASH")
    }

    @Test
    fun `ubuntu tar dir-symlink entries materialize and do not clobber`() {
        // The real Ubuntu archive ships bin -> usr/bin, sbin -> usr/sbin,
        // lib -> usr/lib as directory symlinks (typeflag '2'). extractTar
        // must materialize them as symlinks and keep going.
        extract(
            listOf(
                tarEntry("usr/bin/inner", "INNER".toByteArray()),
                tarEntry("bin", typeflag = '2', linkName = "usr/bin"),
                tarEntry("bin/inner", "OVERWRITTEN-FILE-VIA-LINK".toByteArray()),
            ),
            prefixes = setOf("usr/bin/", "bin"),
        )
        val link = tmp.root.resolve("bin").toPath()
        assertTrue("bin must be a symlink", Files.isSymbolicLink(link))
        assertEquals("usr/bin", Files.readSymbolicLink(link).toString())
        assertFile("usr/bin/inner", "OVERWRITTEN-FILE-VIA-LINK")
    }

    @Test
    fun `truncated archive fails instead of writing partial files`() {
        // A declared 4096-byte entry that the stream can only supply 100
        // bytes of must FAIL the extraction (EOFException) and leave no
        // partial file behind — silent half-files booted a corrupt rootfs.
        val big = ByteArray(4096) { it.toByte() }
        val bytes = tarEntry("usr/bin/truncated", big) + ByteArray(100) { 'X'.code.toByte() } + ByteArray(1024)
        val ex = try {
            extractTar(java.io.ByteArrayInputStream(bytes), tmp.root, null)
            null
        } catch (e: java.io.EOFException) {
            e
        }
        assertTrue("expected EOFException, got $ex", ex != null)
        assertFalse("partial file must not remain", tmp.root.resolve("usr/bin/truncated").exists())
    }

    @Test
    fun `path traversal entries are rejected`() {
        // Hostile archive: ../ escape must be skipped, not materialized.
        extract(
            listOf(
                tarEntry("usr/bin/ok", "OK".toByteArray()),
                tarEntry("../../etc/evil", "PWNED".toByteArray()),
            ),
            prefixes = setOf("usr/bin/", "etc"),
        )
        assertFile("usr/bin/ok", "OK")
        assertFalse("traversal entry must not escape", tmp.root.resolve("../../etc/evil").exists())
        // Nothing landed outside the temp root (canonical containment).
        val parent = tmp.root.parentFile
        parent.listFiles()?.filter { it.name.startsWith("evil") || it.name == "etc" && it !in tmp.root.listFiles().toList() }
            ?.let { for (f in it) assertFalse("no spill: ${f.absolutePath}", f.exists() && f.absolutePath.startsWith(tmp.root.absolutePath).not()) }
    }

    @Test
    fun `hardlink with traversal target is skipped`() {
        extract(
            listOf(
                tarEntry("usr/bin/test", "COREUTILS-TEST".toByteArray()),
                tarEntry("usr/bin/evil-link", typeflag = '1', linkName = "../../../etc/passwd"),
            ),
            prefixes = setOf("usr/bin/"),
        )
        assertFile("usr/bin/test", "COREUTILS-TEST")
        assertFalse("unsafe hardlink must not be materialized", tmp.root.resolve("usr/bin/evil-link").exists())
    }

    @Test
    fun `hardlink entries are materialized as file copies`() {
        // Ubuntu ships real hardlinks (e.g. /usr/bin/[ vs /usr/bin/test).
        // extractTar copies the target content (typeflag '1' path).
        extract(
            listOf(
                tarEntry("usr/bin/test", "COREUTILS-TEST".toByteArray()),
                tarEntry("usr/bin/[", typeflag = '1', linkName = "usr/bin/test"),
            ),
            prefixes = setOf("usr/bin/"),
        )
        assertFile("usr/bin/test", "COREUTILS-TEST")
        assertFile("usr/bin/[", "COREUTILS-TEST")
    }

    @Test
    fun `executable bit is preserved from the tar mode`() {
        extract(
            listOf(
                tarEntry("bin/exec", "run".toByteArray(), mode = 0b111_101_101), // 0755
                tarEntry("bin/plain", "data".toByteArray(), mode = 0b110_100_100), // 0644
            ),
            prefixes = setOf("bin/"),
        )
        assertTrue("0755 entry must be executable", tmp.root.resolve("bin/exec").canExecute())
        assertFalse("0644 entry must not be executable", tmp.root.resolve("bin/plain").canExecute())
    }

    @Test
    fun `no prefixes extracts everything like before`() {
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("etc/hosts", "127.0.0.1 localhost".toByteArray()),
            ),
        )
        assertFile("bin/bash", "#!/bin/bash\n")
        assertFile("etc/hosts", "127.0.0.1 localhost")
    }

    @Test
    fun `dot-slash prefixed entries match onlyPrefixes like legacy archives`() {
        // Alpine minirootfs archives stored every entry with a "./" prefix
        // ("./bin/bash", ...). Ubuntu doesn't prefix, but the normalization
        // must keep working — regression for the targeted restore silently
        // extracting nothing.
        val entries = listOf(
            tarEntry("./usr/bin/bash", "#!/bin/bash\n".toByteArray()),
            tarEntry("./usr/bin/dpkg", "DPKG-BIN".toByteArray()),
            tarEntry("./usr/bin/apt-get", "APT-BIN".toByteArray()),
            tarEntry("./etc/ignored", "skip".toByteArray()),
        )
        extract(entries, setOf("usr/bin/bash", "usr/bin/dpkg", "usr/bin/apt-get"))
        assertFile("usr/bin/bash", "#!/bin/bash\n")
        assertFile("usr/bin/dpkg", "DPKG-BIN")
        assertFile("usr/bin/apt-get", "APT-BIN")
        assertFalse(tmp.root.resolve("etc/ignored").exists())
    }

    @Test
    fun `dot-slash prefixed symlink chain restores sh pointing at dash`() {
        val entries = listOf(
            tarEntry("./usr/bin/dash", "DASH-BIN".toByteArray()),
            tarEntry("./bin/sh", typeflag = '2', linkName = "/usr/bin/dash"),
        )
        extract(entries, setOf("bin/sh", "usr/bin/dash"))
        assertFile("usr/bin/dash", "DASH-BIN")
        val link = tmp.root.resolve("bin/sh").toPath()
        assertTrue("missing symlink bin/sh", Files.isSymbolicLink(link))
        assertEquals("/usr/bin/dash", Files.readSymbolicLink(link).toString())
    }
}
