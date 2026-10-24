package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-step-output-parity] A step preview must keep the metadata
 * banner that most tools put on their FIRST line.
 */
class SubagentStepOutputTrimTest {

    /** The banner file_read emits — the thing that was being dropped. */
    private val fileReadBanner = "[/tmp/a.txt | 4096 bytes | 300 lines | showing 1-200 of 300]"

    private fun lines(n: Int, prefix: String = "line"): List<String> =
        (1..n).map { "$prefix-$it" }

    @Test
    fun `a short output is never clipped`() {
        val out = "a\nb\nc"
        assertEquals(out, SubagentStepOutputTrim.trim("file_read", out))
    }

    @Test
    fun `an empty output is returned unchanged`() {
        assertEquals("", SubagentStepOutputTrim.trim("file_read", ""))
    }

    @Test
    fun `a non-shell tool keeps the HEAD, so the metadata banner survives`() {
        val full = (listOf(fileReadBanner) + lines(300)).joinToString("\n")
        val trimmed = SubagentStepOutputTrim.trim("file_read", full)

        assertTrue(
            "the metadata banner must survive the clip",
            trimmed.startsWith(fileReadBanner),
        )
        assertTrue(
            "a clipped preview must say so",
            trimmed.contains(SubagentStepOutputTrim.TRUNCATION_MARKER),
        )
    }

    @Test
    fun `a non-shell tool does NOT keep the tail`() {
        val full = (listOf(fileReadBanner) + lines(300)).joinToString("\n")
        val trimmed = SubagentStepOutputTrim.trim("file_read", full)
        assertFalse(
            "the tail of a file_read is not the metadata",
            trimmed.contains("line-300"),
        )
    }

    @Test
    fun `shell keeps the TAIL`() {
        val full = lines(300).joinToString("\n")
        val trimmed = SubagentStepOutputTrim.trim("shell_execute", full)
        assertTrue("the tail of a shell stream is the answer", trimmed.contains("line-300"))
        assertFalse(trimmed.contains("line-1\n"))
    }

    @Test
    fun `shell output that fits is not clipped`() {
        val full = lines(10).joinToString("\n")
        assertEquals(full, SubagentStepOutputTrim.trim("shell_execute", full))
    }

    @Test
    fun `every non-shell tool keeps the head`() {
        // The rule is by SHAPE, not by a list of tool names — a tool added
        // tomorrow must not silently fall back to tail-clipping.
        for (name in listOf("file_read", "file_write", "file_edit", "browser_use", "web_search", "memory_get")) {
            val full = (listOf("[$name | header]") + lines(300)).joinToString("\n")
            val trimmed = SubagentStepOutputTrim.trim(name, full)
            assertTrue(
                "$name must keep its head",
                trimmed.startsWith("[$name | header]"),
            )
        }
    }

    @Test
    fun `the shell budget is larger than the head budget`() {
        // Mirrors the main loop's T263 numbers.
        assertEquals(80, SubagentStepOutputTrim.maxLinesFor("shell_execute"))
        assertEquals(60, SubagentStepOutputTrim.maxLinesFor("file_read"))
    }

    @Test
    fun `the main loop keeps a non-shell tool whole`() {
        // The main loop's behaviour is unchanged by this fix: only
        // shell_execute is clipped there. The shared object must make that
        // explicit rather than silently re-clipping it.
        val full = (listOf(fileReadBanner) + lines(300)).joinToString("\n")
        assertTrue(SubagentStepOutputTrim.trim("file_read", full, maxLines = Int.MAX_VALUE) == full)
    }

    @Test
    fun `the clip respects the requested budget`() {
        val full = lines(300).joinToString("\n")
        val trimmed = SubagentStepOutputTrim.trim("file_read", full, maxLines = 10)
        val kept = trimmed.lines().filter { it != SubagentStepOutputTrim.TRUNCATION_MARKER }
        assertEquals(10, kept.size)
    }

    @Test
    fun `the old uniform tail-clip would have dropped the banner`() {
        // Pins WHY this exists: the old rule loses the metadata.
        val full = (listOf(fileReadBanner) + lines(300)).joinToString("\n")
        val oldWay = full.lines().takeLast(60).joinToString("\n")
        assertFalse(
            "the old uniform tail-clip loses the banner",
            oldWay.contains(fileReadBanner),
        )
    }
}
