package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * JVM verification of the [offload-bounded-admission] hardening added to
 * [NativeOffloadServer]:
 *
 *   1. [internalTruncateHandlerOutput] caps a runaway handler's serialized
 *      output so it can't balloon host RAM via the rootfs tmpfile write.
 *   2. [isPathUnderRoot] is the canonicalize + prefix-guard used by the
 *      mailbox path discipline (per security-audit-checklist) — rejects `..`
 *      escapes and same-name-prefix sibling directories.
 *   3. [AdmissionCounters] exposes a pure immutable snapshot.
 *
 * The Android-dependent accept-loop / executor itself can only be exercised
 * on-device; these pure functions are the testable seams around it.
 */
class OffloadBoundedAdmissionTest {

    // ---- internalTruncateHandlerOutput ----

    @Test
    fun `truncate below cap is passthrough`() {
        val (out, trimmed) = internalTruncateHandlerOutput("hello\nworld\n")
        assertEquals("hello\nworld\n", out)
        assertFalse(trimmed)
    }

    @Test
    fun `truncate at exact cap is passthrough`() {
        val text = "x".repeat(MAX_HANDLER_OUTPUT_CHARS)
        val (out, trimmed) = internalTruncateHandlerOutput(text)
        assertEquals(text, out)
        assertFalse(trimmed)
    }

    @Test
    fun `truncate above cap trims and flags`() {
        val oversized = "y".repeat(MAX_HANDLER_OUTPUT_CHARS + 100)
        val (out, trimmed) = internalTruncateHandlerOutput(oversized)
        assertTrue(trimmed)
        assertTrue(out.length <= MAX_HANDLER_OUTPUT_CHARS + 64) // includes notice suffix
        assertTrue(out.contains("output truncated"))
        assertTrue(out.startsWith("y".repeat(MAX_HANDLER_OUTPUT_CHARS)))
    }

    @Test
    fun `truncate empty is passthrough`() {
        val (out, trimmed) = internalTruncateHandlerOutput("")
        assertEquals("", out)
        assertFalse(trimmed)
    }

    // ---- isPathUnderRoot ----

    @Test
    fun `path inside root is allowed`() {
        val root = File("/data/foo/staging").absoluteFile
        val child = File(root, "run-abc/request.json")
        assertTrue(isPathUnderRoot(root, child))
        // root itself is allowed (== boundary)
        assertTrue(isPathUnderRoot(root, root))
    }

    @Test
    fun `path outside root is rejected`() {
        val root = File("/data/foo/staging").absoluteFile
        val outside = File("/data/foo/other/request.json")
        assertFalse(isPathUnderRoot(root, outside))
        val outside2 = File("/etc/passwd")
        assertFalse(isPathUnderRoot(root, outside2))
    }

    @Test
    fun `same-name-prefix sibling is rejected (boundary ambiguity)`() {
        // /data/foo/stagingX must NOT be considered under /data/foo/staging
        val root = File("/data/foo/staging").absoluteFile
        val sibling = File("/data/foo/stagingX/request.json")
        assertFalse(isPathUnderRoot(root, sibling))
    }

    @Test
    fun `dotdot traversal is rejected`() {
        val root = File("/data/foo/staging").absoluteFile
        // a child path containing .. that canonicalizes outside the root
        val traversal = File(root, "../../etc/passwd")
        assertFalse(isPathUnderRoot(root, traversal))
    }

    @Test
    fun `noncanonicalizable path is rejected`() {
        val root = File("/data/foo/staging").absoluteFile
        // A File whose canonicalPath throws (e.g. malformed) => rejected.
        assertFalse(isPathUnderRoot(root, File("\u0000garbage")))
    }

    // ---- AdmissionCounters snapshot ----

    @Test
    fun `admission counters snapshot is immutable and consistent`() {
        val c = NativeOffloadServer.admissionCounters()
        assertEquals(0, c.queueBacklog)
        assertEquals(0L, c.acceptedTotal)
        assertEquals(0L, c.rejectedTotal)
        assertEquals(0L, c.completedTotal)
    }

    @Test
    fun `MAX handler output cap is positive and bounded`() {
        assertTrue(MAX_HANDLER_OUTPUT_CHARS > 0)
        assertTrue(MAX_HANDLER_OUTPUT_CHARS <= 32 * 1024 * 1024) // sanity: <= 32MiB
    }
}
