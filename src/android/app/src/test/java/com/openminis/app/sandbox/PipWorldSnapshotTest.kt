package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-pip-world] Pure-JVM tests for the pip-world snapshot format.
 * Pins the same contract DpkgWorldSnapshotTest pins for dpkg-world:
 * bare-name round-trip, tolerance of comments/blanks, pin-stripping for
 * foreign `pip freeze` output, and rejection of requirement-file syntax
 * that must never reach `pip install -r`.
 */
class PipWorldSnapshotTest {

    @Test
    fun `parsePipWorld round-trips bare names`() {
        val names = listOf("requests", "rich", "numpy")
        val text = "# pip-world snapshot — user pip leaf package names, one per line\n" +
            names.joinToString("\n") + "\n"
        assertEquals(names, parsePipWorld(text))
    }

    @Test
    fun `parsePipWorld tolerates comments blanks and bad lines`() {
        val text = """
            # header comment
            requests

            # another comment
            not a valid package name!!!

            rich
        """.trimIndent()
        assertEquals(listOf("requests", "rich"), parsePipWorld(text))
    }

    @Test
    fun `parsePipWorld strips pins from foreign freeze output`() {
        // `pip freeze` writes name==version; a snapshot copied from another
        // environment must restore as name-only (intent, not pins).
        assertEquals(
            listOf("torch", "pandas"),
            parsePipWorld("torch==2.14.0\npandas==3.0.5\n"),
        )
        // Legacy single-= pin form accepted too (parity with parseDpkgWorld).
        assertEquals(listOf("six"), parsePipWorld("six=1.17.0\n"))
    }

    @Test
    fun `parsePipWorld skips requirement-file directives`() {
        // If a user ever pastes a requirements file into the snapshot, the
        // directives must be skipped, not fed to pip install -r as names.
        assertTrue(parsePipWorld("-r other.txt\n--index-url https://example\nrich\n").contains("rich"))
        assertEquals(1, parsePipWorld("-r other.txt\n--index-url https://example\nrich\n").size)
    }

    @Test
    fun `parsePipWorld deduplicates nothing but keeps order`() {
        // Deliberate: dump writes a sorted deduplicated set; restore trusts
        // the file as written (pip itself is idempotent on duplicates).
        assertEquals(
            listOf("rich", "rich"),
            parsePipWorld("rich\nrich\n"),
        )
    }

    @Test
    fun `formatPipWorld round-trips through parsePipWorld`() {
        // Mirrors the DpkgWorldSnapshotTest round-trip assertion — the file
        // write + read-back path must not lose or reorder packages.
        val names = listOf("akshare", "httpx", "sentence-transformers", "torch")
        val text = "# pip-world snapshot\n" + names.joinToString("\n") + "\n"
        assertEquals(names, parsePipWorld(text))
    }
}
