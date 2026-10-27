package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The hard-forbidden capabilities must be
 * unreachable by ANY configuration.
 *
 * `SUBAGENT_HARD_FORBIDDEN` says so in its own documentation — "not by
 * the default, not by a skill's `allowed_tools`, not by any future
 * config knob" — and the check that enforces it was MISSING from
 * `isToolGrantableToSubagent`. The capabilities were blocked only
 * because nobody had happened to add them to the base set.
 *
 * That makes a plausible-looking edit — adding one to the base set — a
 * silent privilege escalation, with the documentation still promising
 * the opposite.
 */
class SubagentHardForbiddenTest {

    @Test
    fun `the memory capabilities are not grantable`() {
        assertFalse(
            "memory_get must never be grantable to a sub-agent",
            AgentCapabilities.isToolGrantableToSubagent("memory_get"),
        )
        assertFalse(
            "memory_write must never be grantable to a sub-agent",
            AgentCapabilities.isToolGrantableToSubagent("memory_write"),
        )
    }

    @Test
    fun `spawning further sub-agents is not grantable`() {
        assertFalse(
            "spawn_agent must never be grantable to a sub-agent",
            AgentCapabilities.isToolGrantableToSubagent("spawn_agent"),
        )
        assertFalse(
            "join_subagents must never be grantable to a sub-agent",
            AgentCapabilities.isToolGrantableToSubagent("join_subagents"),
        )
    }

    /**
     * THE test: a hard-forbidden capability stays forbidden even when the
     * base set contains it.
     *
     * The other tests in this file cannot catch a missing hard-forbidden
     * check — `memory_get` is refused either way, because it is absent
     * from the base set. The only way to tell the two defences apart is
     * to hand the rule a base set that DOES contain the capability.
     *
     * This is the question a future edit to the base set turns on, so it
     * is the one that has to be pinned.
     */
    @Test
    fun `a hard-forbidden capability stays forbidden even when the base set contains it`() {
        val everything = AgentCapability.entries.toSet()
        // Only tools that MAP to a capability reach the hard-forbidden
        // check: an unmapped tool is refused one step earlier, by the
        // fail-closed `?: return false`. A tool that is not in the
        // capability map at all — or one whose name is simply wrong — is
        // such a tool: it would test the wrong branch and pass whether
        // or not the hard-forbidden check exists. (The first version of
        // this test used `memory_read`, which does not exist; the
        // capability map calls it `memory_get`. The test passed, and was
        // guarding nothing.)
        for (tool in listOf("memory_get", "memory_write", "spawn_agent", "join_subagents")) {
            assertFalse(
                "$tool must stay forbidden even if every capability were grantable",
                AgentCapabilities.isGrantable(
                    toolName = tool,
                    base = everything,
                    hardForbidden = AgentCapabilities.SUBAGENT_HARD_FORBIDDEN,
                ),
            )
        }
    }

    /**
     * And the converse: a capability that is neither hard-forbidden nor
     * in the base set is still refused. The rule has both conditions.
     */
    @Test
    fun `a capability outside the base set is refused`() {
        assertFalse(
            "todo_write must be refused when it is not in the base set",
            AgentCapabilities.isGrantable(
                toolName = "todo_write",
                base = emptySet(),
                hardForbidden = emptySet(),
            ),
        )
    }

    /**
     * The two lists are ONE list.
     *
     * `SUBAGENT_HARD_FORBIDDEN` used to be a hand-written copy of what
     * `SubagentSkill.FORBIDDEN_TOOLS` already said, and nothing in the
     * production code consulted it. Two descriptions of one rule, one of
     * them unreachable, and a future edit touching only one would have
     * widened the grant silently.
     */
    /**
     * The two lists describe ONE rule, and the test pins BOTH directions.
     *
     * The first version compared the derived set with
     * `SUBAGENT_HARD_FORBIDDEN` — but that set WAS the derived one, so
     * the comparison was always equal and the test guarded nothing. It
     * passed even with the derivation removed.
     *
     * Comparing against the MAPPING is what makes it a real check:
     *
     *  - every forbidden tool that HAS a capability contributes it, so a
     *    tool added to `FORBIDDEN_TOOLS` without updating the
     *    capability-level list is caught;
     *  - every hard-forbidden capability comes from a forbidden tool, so
     *    a capability added to the list on its own is caught.
     *
     * Either drift is a silent widening of what a sub-agent can reach.
     */
    @Test
    fun `the hard-forbidden capabilities and the forbidden tools describe one rule`() {
        val mapped = SubagentSkill.FORBIDDEN_TOOLS
            .mapNotNull { AgentCapabilities.capabilityOf(it) }
            .toSet()

        // Direction 1: nothing forbidden at the tool level may be
        // missing from the capability level.
        for (tool in SubagentSkill.FORBIDDEN_TOOLS) {
            val capability = AgentCapabilities.capabilityOf(tool) ?: continue
            assertTrue(
                "$tool is forbidden as a tool, so its capability $capability " +
                    "must be hard-forbidden too",
                capability in AgentCapabilities.SUBAGENT_HARD_FORBIDDEN,
            )
        }

        // Direction 2: nothing hard-forbidden may come from nowhere. A
        // capability in the set with no forbidden tool behind it is a
        // rule with no stated reason — and a future edit to the base set
        // would then be silently widening a grant.
        for (capability in AgentCapabilities.SUBAGENT_HARD_FORBIDDEN) {
            assertTrue(
                "capability $capability is hard-forbidden but no forbidden " +
                    "tool maps to it",
                capability in mapped,
            )
        }
    }

    /**
     * The check must not be a tautology.
     *
     * The first version of this test compared the derived set with
     * `SUBAGENT_HARD_FORBIDDEN` — but that set WAS the derived one, so
     * the comparison was always equal and the test guarded nothing. It
     * passed even with the derivation removed.
     *
     * This one compares against a fact that is NOT derived: a tool that
     * is NOT forbidden must not contribute a capability to the
     * hard-forbidden set. `todo_write` is such a tool — it is excluded
     * from the grantable base set for a different reason (it replaces
     * the user's session task list), and it is deliberately not on the
     * forbidden list.
     */

    @Test
    fun `a tool that is not forbidden does not make its capability hard forbidden`() {
        assertFalse(
            "todo_write is not in FORBIDDEN_TOOLS, so TASK_LIST must not be " +
                "hard-forbidden",
            AgentCapability.TASK_LIST in AgentCapabilities.SUBAGENT_HARD_FORBIDDEN,
        )
        assertFalse(
            "todo_write is not in FORBIDDEN_TOOLS",
            "todo_write" in SubagentSkill.FORBIDDEN_TOOLS,
        )
    }

    /**
     * The base set really does grant the ordinary capabilities.
     */
    @Test
    fun `a capability in the base set is granted`() {
        assertTrue(
            AgentCapabilities.isGrantable(
                toolName = "file_read",
                base = AgentCapabilities.SUBAGENT_BASE,
                hardForbidden = AgentCapabilities.SUBAGENT_HARD_FORBIDDEN,
            ),
        )
    }

    /**
     * The task list is excluded from the grantable base set — NOT because
     * the line was forgotten, but because `todo_write` replaces the whole
     * session task list the USER sees, so a sub-agent writing to it would
     * overwrite the parent's plan underneath them.
     */
    @Test
    fun `the session task list is not grantable`() {
        assertFalse(
            "todo_write must not be grantable to a sub-agent",
            AgentCapabilities.isToolGrantableToSubagent("todo_write"),
        )
    }

    @Test
    fun `the ordinary capabilities are grantable`() {
        assertTrue(AgentCapabilities.isToolGrantableToSubagent("file_read"))
        assertTrue(AgentCapabilities.isToolGrantableToSubagent("shell_execute"))
        assertTrue(AgentCapabilities.isToolGrantableToSubagent("web_search"))
    }
}
