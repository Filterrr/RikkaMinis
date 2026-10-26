package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The roster is the parent's ONLY map of what
 * it can spawn.
 *
 * Before this existed, `skill_name` said "the name of the sub-agent skill"
 * and nothing else: the parent had to guess, and the generic skills
 * fragment (capped at 20 entries, mixing sub-agent skills with ordinary
 * ones, carrying no marker) could easily leave the sub-agent skills out
 * entirely. A wrong guess cost the whole delegation for the turn.
 *
 * These tests pin the two properties that make the roster usable:
 *  - it NAMES the spawnable sub-agents (discoverability);
 *  - it stays inside a token budget (it rides in every request's schema).
 */
class SubagentRosterHintTest {

    private fun entry(name: String, description: String) = SubagentRosterHint.Entry(name, description)

    // ── rendering ─────────────────────────────────────────────────────────

    @Test
    fun `an empty roster renders nothing`() {
        assertEquals("", SubagentRosterHint.build(emptyList()))
    }

    @Test
    fun `the roster names every spawnable sub-agent`() {
        val hint = SubagentRosterHint.build(
            listOf(
                entry("general-agent", "Deep research and large codebase exploration."),
                entry("read-only-researcher", "Read-only investigation."),
            ),
        )
        assertTrue("the parent must see the name", hint.contains("general-agent"))
        assertTrue(hint.contains("read-only-researcher"))
        assertTrue("the description is the routing text", hint.contains("Deep research"))
        assertTrue(hint.contains("Read-only investigation"))
    }

    @Test
    fun `the roster says how to use it`() {
        val hint = SubagentRosterHint.build(listOf(entry("general-agent", "x")))
        assertTrue(
            "the parent must be told to pick a listed name, not invent one",
            hint.contains("exact name"),
        )
    }

    @Test
    fun `a blank name is skipped`() {
        val hint = SubagentRosterHint.build(
            listOf(entry("  ", "no name"), entry("real-agent", "ok")),
        )
        assertFalse(hint.contains("no name"))
        assertTrue(hint.contains("real-agent"))
    }

    @Test
    fun `a blank description does not leave an empty gap`() {
        val hint = SubagentRosterHint.build(listOf(entry("general-agent", "   ")))
        assertTrue(hint.contains("general-agent"))
        assertFalse("no dangling separator", hint.contains("- \n"))
    }

    // ── budget ────────────────────────────────────────────────────────────

    @Test
    fun `the roster is capped so it cannot blow the request budget`() {
        val many = (1..80).map { entry("agent-$it", "description $it") }
        val hint = SubagentRosterHint.build(many)
        assertTrue(
            "roster must stay bounded (was ${hint.length} chars)",
            hint.length <= SubagentRosterHint.MAX_CHARS,
        )
        assertTrue("the first entries are the ones kept", hint.contains("agent-1"))
    }

    @Test
    fun `the entry count is capped too`() {
        val many = (1..80).map { entry("agent-$it", "d") }
        val hint = SubagentRosterHint.build(many)
        val listed = hint.lines().count { it.trim().startsWith("- ") }
        assertTrue(
            "at most ${SubagentRosterHint.MAX_ENTRIES} entries (was $listed)",
            listed <= SubagentRosterHint.MAX_ENTRIES,
        )
    }

    @Test
    fun `truncation says so, and says that the exact name still works`() {
        val many = (1..80).map { entry("agent-$it", "d") }
        val hint = SubagentRosterHint.build(many)
        assertTrue("a truncated roster must say it is truncated", hint.contains("truncated"))
    }

    @Test
    fun `a roster that fits is not marked as truncated`() {
        val hint = SubagentRosterHint.build(listOf(entry("general-agent", "d")))
        assertFalse(hint.contains("truncated"))
    }

    // ── merging the two routes ───────────────────────────────────────────

    /**
     * The skill wins on a name clash — it is the thing that can actually
     * RUN. But the configured description is the ROUTING text (the user
     * wrote it to say WHEN to delegate), so it replaces the skill's own.
     */
    @Test
    fun `a skill wins a name clash but keeps the configured routing text`() {
        val merged = SubagentRosterHint.merge(
            skills = listOf(entry("general-agent", "runs general tasks")),
            configured = listOf(entry("general-agent", "use for deep research")),
        )
        assertEquals(1, merged.size)
        assertEquals("general-agent", merged[0].name)
        assertEquals("use for deep research", merged[0].description)
    }

    @Test
    fun `an empty configured description does not erase the skill's own`() {
        val merged = SubagentRosterHint.merge(
            skills = listOf(entry("general-agent", "runs general tasks")),
            configured = listOf(entry("general-agent", "")),
        )
        assertEquals("runs general tasks", merged[0].description)
    }

    @Test
    fun `a configured entry with no skill is kept - it names a speciality`() {
        val merged = SubagentRosterHint.merge(
            skills = listOf(entry("general-agent", "runs general tasks")),
            configured = listOf(entry("security-reviewer", "use before merging")),
        )
        assertEquals(2, merged.size)
        assertEquals("general-agent", merged[0].name)
        assertEquals("security-reviewer", merged[1].name)
    }

    @Test
    fun `skills come first so a truncation never drops the spawnable ones`() {
        val merged = SubagentRosterHint.merge(
            skills = listOf(entry("a-skill", "x")),
            configured = listOf(entry("z-configured", "y")),
        )
        assertEquals("a-skill", merged[0].name)
        assertEquals("z-configured", merged[1].name)
    }

    @Test
    fun `a name clash is case and whitespace insensitive`() {
        val merged = SubagentRosterHint.merge(
            skills = listOf(entry("General-Agent", "runs general tasks")),
            configured = listOf(entry("  general-agent  ", "use for deep research")),
        )
        assertEquals(1, merged.size)
        assertEquals("use for deep research", merged[0].description)
    }

    // ── the "unknown name" error ──────────────────────────────────────────

    @Test
    fun `an unknown name lists the valid alternatives`() {
        val msg = SubagentRosterHint.unknownAgentMessage(
            "genral-agent",
            listOf(entry("general-agent", "Deep research.")),
        )
        assertTrue(msg.contains("genral-agent"))
        assertTrue("the correction must be offered", msg.contains("general-agent"))
        assertTrue(
            "the parent must be told to pick a DIFFERENT one, not retry",
            msg.contains("Do not retry"),
        )
    }

    @Test
    fun `an unknown name with no roster stays a plain error`() {
        val msg = SubagentRosterHint.unknownAgentMessage("nope", emptyList())
        assertTrue(msg.contains("nope"))
        assertFalse(
            "with nothing to suggest, do not pretend there are alternatives",
            msg.contains("Available sub-agents"),
        )
    }
}
