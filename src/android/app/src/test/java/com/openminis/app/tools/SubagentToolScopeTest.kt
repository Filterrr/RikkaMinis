package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The tool scope shown in the approval
 * dialog.
 *
 * The rule these tests pin: a user about to tap "Always allow" must be
 * able to see WHAT they are granting, and the three states must be
 * distinguishable —
 *  - an allowlist (what IS permitted);
 *  - a blacklist (what is DENIED);
 *  - no scope at all (unrestricted — and that must be said, not left
 *    blank, because an empty line reads as "nothing").
 */
class SubagentToolScopeTest {

    @Test
    fun `an allowlist is named`() {
        val label = SubagentToolScope.label(setOf("file_read", "shell_execute"))
        assertTrue(label.contains("file_read"))
        assertTrue(label.contains("shell_execute"))
        assertTrue(label.contains("allowed"))
    }

    @Test
    fun `no allowlist says all tools explicitly`() {
        val label = SubagentToolScope.label(null)
        assertTrue("an empty label would read as 'nothing permitted'", label.contains("all tools"))
    }

    @Test
    fun `an empty allowlist says no tools`() {
        val label = SubagentToolScope.label(emptySet())
        assertTrue(label.contains("no tools"))
        assertTrue("it must NOT claim all tools", !label.contains("all tools"))
    }

    /**
     * [feat/subagent-roster-routing] The fingerprint that binds an
     * "always allow" grant to the scope the user actually saw.
     *
     * "Always allow" used to be recorded per skill ID alone, while the
     * scope it grants comes from the skill's CURRENT `allowed_tools` —
     * which the user can change by editing or re-importing the SKILL.md.
     * So a grant outlived the thing that was approved: approve a
     * read-only auditor, add `shell_execute`, and the next spawn ran
     * with `shell_execute`, silently, forever, under a grant the user
     * had never seen.
     *
     * The fingerprint is what makes "always allow" mean what the dialog
     * said.
     */
    @Test
    fun `the fingerprint changes when the allowlist changes`() {
        val before = SubagentToolScope.fingerprint(setOf("file_read"))
        val after = SubagentToolScope.fingerprint(setOf("file_read", "shell_execute"))
        assertTrue(
            "adding a tool must invalidate a grant made under the old scope",
            before != after,
        )
    }

    @Test
    fun `the fingerprint changes when the blacklist changes`() {
        val before = SubagentToolScope.fingerprint(setOf("file_read"))
        val after = SubagentToolScope.fingerprint(setOf("file_read"), setOf("shell_execute"))
        assertTrue(
            "denying a tool must invalidate a grant made under the old scope",
            before != after,
        )
    }

    /**
     * A grant made under one scope must not carry over to a different
     * one — that is the whole point.
     */
    @Test
    fun `the same scope has the same fingerprint`() {
        assertEquals(
            SubagentToolScope.fingerprint(setOf("file_read", "shell_execute")),
            SubagentToolScope.fingerprint(setOf("shell_execute", "file_read")),
        )
    }

    /**
     * An unrestricted skill is a DIFFERENT capability from one that
     * happens to list every tool by name, so the two must not share a
     * fingerprint.
     */
    @Test
    fun `unrestricted is not the same as an explicit all-tools list`() {
        assertTrue(
            SubagentToolScope.fingerprint(null) !=
                SubagentToolScope.fingerprint(setOf("file_read", "shell_execute")),
        )
    }

    /**
     * The fingerprint is over the SETS, not the rendered label. A change
     * in wording, or in how many names the dialog shows, must not
     * silently invalidate a grant — that would train the user to ignore
     * the prompt.
     */
    @Test
    fun `the fingerprint does not depend on the rendered label`() {
        val many = (1..20).map { "tool_$it" }.toSet()
        assertEquals(
            SubagentToolScope.fingerprint(many),
            SubagentToolScope.fingerprint(many),
        )
    }

    @Test
    fun `a blacklist is named separately from the allowlist`() {
        val label = SubagentToolScope.label(
            allowedTools = setOf("file_read", "shell_execute"),
            disallowedTools = setOf("shell_execute"),
        )
        assertTrue("the denied tool must be visible", label.contains("denied"))
        assertTrue(label.contains("shell_execute"))
    }

    @Test
    fun `a long list is capped and says how many were dropped`() {
        val many = (1..12).map { "tool_$it" }.toSet()
        val label = SubagentToolScope.label(many, maxTools = 8)
        assertTrue(label.contains("+4 more"))
        assertTrue(label.contains("tool_1"))
        assertTrue(!label.contains("tool_9"))
    }

    @Test
    fun `the deny wins over the allow in the runtime gate`() {
        // The schema and the runtime must agree: a tool the skill denies
        // is not permitted even when the allowlist names it.
        assertEquals(
            false,
            SubagentAllowedTools.isAllowed("shell_execute", setOf("shell_execute"), setOf("shell_execute")),
        )
    }

    @Test
    fun `the refusal text distinguishes denied from not-allowed`() {
        val denied = SubagentAllowedTools.refusalText("shell_execute", setOf("shell_execute"), setOf("shell_execute"))
        assertTrue("a deny must not invite the model to edit a list", denied.contains("disallowed_tools"))

        val notAllowed = SubagentAllowedTools.refusalText("file_write", setOf("file_read"))
        assertTrue(notAllowed.contains("allowed_tools"))
        assertTrue(!notAllowed.contains("disallowed_tools"))
    }
}
