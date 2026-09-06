package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-message-fork] Unit tests for the pure fork helpers. ForkMapper touches
 * only java.io.File — plain JVM tests, no Android deps.
 */
class ForkMapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── media path remapping ────────────────────────────────────────────

    @Test
    fun `media path remap swaps only the session segment`() {
        val out = ForkMapper.remapMediaRelativePath(
            "2026/09/06/old-sid/abc123.png", "old-sid", "new-sid",
        )
        assertEquals("2026/09/06/new-sid/abc123.png", out)
    }

    @Test
    fun `path without session segment is unchanged`() {
        val out = ForkMapper.remapPartsJson("plain text content", "old-sid", "new-sid")
        assertEquals("plain text content", out)
    }

    @Test
    fun `parts json with session-scoped media path is remapped`() {
        val parts = """{"type":"media","value":{"relativePath":"2026/09/06/from-sid/x.jpg"}}"""
        val out = ForkMapper.remapPartsJson(parts, "from-sid", "to-sid")
        assertTrue("/to-sid/" in out)
        assertFalse("/from-sid/" in out)
    }

    @Test
    fun `partial session-id substring does not false-positive`() {
        // "from-sid-longer" contains "from-sid" as a substring but the replace
        // targets "/from-sid/" (segment-bounded), so the longer id survives.
        val parts = """{"p":"2026/01/01/from-sid-longer/a.png"}"""
        val out = ForkMapper.remapPartsJson(parts, "from-sid", "to-sid")
        assertTrue("/from-sid-longer/" in out)
    }

    // ── cutoff ──────────────────────────────────────────────────────────

    @Test
    fun `cutoff is max of anchor and merged tails`() {
        assertEquals(7, ForkMapper.cutoffSortOrder(7))
        assertEquals(9, ForkMapper.cutoffSortOrder(7, listOf(9, 8)))
    }

    // ── naming / provenance ─────────────────────────────────────────────

    @Test
    fun `forked title prefixes parent title`() {
        assertEquals("分支 · 深入探讨", ForkMapper.forkedTitle("深入探讨"))
        assertEquals("分支 · 未命名会话", ForkMapper.forkedTitle(null))
        assertEquals("分支 · 未命名会话", ForkMapper.forkedTitle("   "))
    }

    @Test
    fun `fork source encodes session and anchor`() {
        assertEquals("fork:s1:m2", ForkMapper.forkSource("s1", "m2"))
    }

    // ── file copies ─────────────────────────────────────────────────────

    @Test
    fun `copySessionMedia copies date dirs and counts files`() {
        val base = tmp.newFolder("media")
        val dateDir = File(base, "2026/09/06/from-sid").apply { mkdirs() }
        File(dateDir, "a.png").writeBytes(byteArrayOf(1))
        File(dateDir, "b.jpg").writeBytes(byteArrayOf(2))

        val copied = ForkMapper.copySessionMedia(base, "from-sid", "to-sid")
        assertEquals(2, copied)
        val dst = File(base, "2026/09/06/to-sid")
        assertTrue(File(dst, "a.png").exists())
        assertTrue(File(dst, "b.jpg").exists())
        // Source untouched — the original session keeps its media.
        assertTrue(File(dateDir, "a.png").exists())
    }

    @Test
    fun `copySessionMedia no-op for text-only session`() {
        val base = tmp.newFolder("media")
        assertEquals(0, ForkMapper.copySessionMedia(base, "from-sid", "to-sid"))
    }

    @Test
    fun `copySessionResourceDir copies known subdirs only`() {
        val root = tmp.newFolder("minis-sessions")
        val src = File(root, "from-sid")
        File(src, "attachments").apply { mkdirs() }
        File(src, "attachments/file.txt").writeText("hi")
        File(src, "workspace").apply { mkdirs() }
        File(src, "unrelated").apply { mkdirs() } // must NOT be copied
        File(src, "unrelated/secret.txt").writeText("x")

        val copied = ForkMapper.copySessionResourceDir(root, "from-sid", "to-sid")
        assertEquals(2, copied) // attachments/file.txt + the workspace dir entry
        assertTrue(File(root, "to-sid/attachments/file.txt").exists())
        assertFalse(File(root, "to-sid/unrelated").exists())
    }
}
