package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/subagent-allowed-tools-enforcement] Pins the runtime allowlist gate.
 *
 * The defect: `allowed_tools` was enforced ONLY in the schema filter
 * ([SubagentSkill.buildFilteredTools]). The runtime dispatcher
 * ([SubagentRunner.executeSubagentTool]) checked the hard-forbidden set and
 * the capability catalog, but never the allowlist — so a skill that declared
 * `allowed_tools: [file_read, read_image, browser_use]` could still have
 * `file_write` / `shell_execute` EXECUTED when the model emitted those names
 * anyway.
 *
 * Measured on the real runtime (2026-10-22, subagent-4): a
 * `read-only-researcher` run wrote `repro_write.txt` with `file_write` and
 * created `repro_shell.txt` with `shell_execute`; both files landed on disk
 * and the parent's md5 check matched.
 */
class SubagentAllowedToolsTest {

    private val readOnly = setOf("file_read", "read_image", "browser_use")

    @Test
    fun `a tool inside the allowlist is allowed`() {
        assertTrue(SubagentAllowedTools.isAllowed("file_read", readOnly))
        assertTrue(SubagentAllowedTools.isAllowed("read_image", readOnly))
        assertTrue(SubagentAllowedTools.isAllowed("browser_use", readOnly))
    }

    @Test
    fun `a tool outside the allowlist is refused`() {
        assertFalse(SubagentAllowedTools.isAllowed("file_write", readOnly))
        assertFalse(SubagentAllowedTools.isAllowed("file_edit", readOnly))
        assertFalse(SubagentAllowedTools.isAllowed("shell_execute", readOnly))
        assertFalse(SubagentAllowedTools.isAllowed("web_search", readOnly))
    }

    @Test
    fun `a null allowlist means unrestricted`() {
        assertTrue(SubagentAllowedTools.isAllowed("shell_execute", null))
        assertTrue(SubagentAllowedTools.isAllowed("file_write", null))
    }

    @Test
    fun `an empty allowlist allows nothing`() {
        // `allowed_tools: []` parses to an empty set, which means "no tools",
        // NOT "no restriction" — the distinction is load-bearing.
        assertFalse(SubagentAllowedTools.isAllowed("file_read", emptySet()))
        assertFalse(SubagentAllowedTools.isAllowed("shell_execute", emptySet()))
    }

    @Test
    fun `the refusal names the allowlist and the next step`() {
        val text = SubagentAllowedTools.refusalText("shell_execute", readOnly)
        assertTrue(text.contains("shell_execute"))
        assertTrue(text.contains("allowed_tools"))
        assertTrue(text.contains("file_read"))
        assertTrue(text.contains("browser_use"))
        assertTrue(
            "the refusal must tell the model not to retry",
            text.contains("Do NOT retry"),
        )
    }

    @Test
    fun `the refusal for an unrestricted skill does not invent an allowlist`() {
        val text = SubagentAllowedTools.refusalText("shell_execute", null)
        assertTrue(text.contains("unrestricted"))
    }
}
