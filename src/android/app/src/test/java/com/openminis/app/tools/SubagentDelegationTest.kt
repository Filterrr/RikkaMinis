package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The routing table that connects the
 * CONFIGURED roster (the TRAE shape: a speciality with a description) to a
 * RUNTIME (a sub-agent skill).
 *
 * Before this existed, the configured roster was a complete data model with
 * NO runtime: `SubAgentRoster.resolve` had zero call sites and the
 * `subagent_task` tool was never registered. A user could configure
 * "security-reviewer" and nothing would ever use it.
 */
class SubagentDelegationTest {

    private fun skill(name: String, description: String = "d") =
        SubagentRosterHint.Entry(name = name, description = description)

    private fun configured(
        name: String,
        description: String = "use for security review",
        instructions: String = "check every input for injection",
    ) = SubagentDelegation.ConfiguredAgent(name, description, instructions)

    // ── a bare skill name ────────────────────────────────────────────────

    @Test
    fun `a plain skill name resolves to that skill with no extra instructions`() {
        val target = SubagentDelegation.resolve(
            requested = "general-agent",
            skillRoster = listOf(skill("general-agent"), skill("read-only-researcher")),
            configured = emptyList(),
            skillExists = { true },
        )
        assertNotNull(target)
        assertEquals("general-agent", target!!.skillName)
        assertEquals("", target.extraInstructions)
        assertTrue("a plain skill is not a roster delegation", !target.fromRoster)
    }

    @Test
    fun `an unknown name resolves to null - the caller reports the error`() {
        val target = SubagentDelegation.resolve(
            requested = "nope",
            skillRoster = listOf(skill("general-agent")),
            configured = emptyList(),
            skillExists = { true },
        )
        assertNull(target)
    }

    // ── a configured speciality ──────────────────────────────────────────

    @Test
    fun `a configured entry with no matching skill runs on the general runtime`() {
        val target = SubagentDelegation.resolve(
            requested = "security-reviewer",
            skillRoster = listOf(skill("general-agent")),
            configured = listOf(configured("security-reviewer")),
            skillExists = { it == "general-agent" },
        )
        assertNotNull(target)
        assertEquals("general-agent", target!!.skillName)
        assertEquals("check every input for injection", target.extraInstructions)
        assertTrue(target.fromRoster)
    }

    @Test
    fun `a configured entry that names an installed skill runs THAT skill`() {
        val target = SubagentDelegation.resolve(
            requested = "security-reviewer",
            skillRoster = listOf(skill("general-agent"), skill("security-reviewer")),
            configured = listOf(configured("security-reviewer")),
            skillExists = { true },
        )
        assertNotNull(target)
        assertEquals("security-reviewer", target!!.skillName)
        assertEquals("check every input for injection", target.extraInstructions)
    }

    @Test
    fun `the configured description is the routing text the parent saw`() {
        val target = SubagentDelegation.resolve(
            requested = "security-reviewer",
            skillRoster = listOf(skill("general-agent")),
            configured = listOf(configured("security-reviewer", "use before every merge")),
            skillExists = { it == "general-agent" },
        )
        assertEquals("use before every merge", target!!.description)
    }

    @Test
    fun `a configured entry with empty instructions still routes`() {
        val target = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = listOf(skill("general-agent")),
            configured = listOf(configured("reviewer", instructions = "")),
            skillExists = { it == "general-agent" },
        )
        assertNotNull(target)
        assertEquals("general-agent", target!!.skillName)
        assertEquals("", target.extraInstructions)
    }

    // ── name matching ────────────────────────────────────────────────────

    @Test
    fun `name matching ignores case and surrounding whitespace`() {
        val target = SubagentDelegation.resolve(
            requested = "  Security-Reviewer  ",
            skillRoster = listOf(skill("general-agent")),
            configured = listOf(configured("security-reviewer")),
            skillExists = { it == "general-agent" },
        )
        assertNotNull(target)
        assertTrue(target!!.fromRoster)
    }

    @Test
    fun `a configured entry wins over a skill of the same name - the user wrote it`() {
        // The configured entry carries the MISSION; the skill carries the
        // RUNTIME. When both exist the configured one must not be ignored,
        // otherwise the user's instructions silently do nothing.
        val target = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = listOf(skill("reviewer", "skill description")),
            configured = listOf(configured("reviewer", "configured description")),
            skillExists = { true },
        )
        assertNotNull(target)
        assertEquals("reviewer", target!!.skillName)
        assertEquals("check every input for injection", target.extraInstructions)
        assertEquals("configured description", target.description)
    }

    // ── the general runtime must exist ───────────────────────────────────

    @Test
    fun `the fallback runtime is the general agent - a stable name`() {
        assertEquals("general-agent", SubagentDelegation.GENERAL_SKILL_NAME)
    }

    @Test
    fun `a configured entry whose skill was uninstalled still routes to the general runtime`() {
        // The fallback must not depend on the roster listing the general
        // agent: the roster may be truncated in the hint, but the SKILL
        // still exists on disk.
        val target = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = emptyList(),
            configured = listOf(configured("reviewer")),
            skillExists = { false },
        )
        assertNotNull(target)
        assertEquals("general-agent", target!!.skillName)
    }
}
