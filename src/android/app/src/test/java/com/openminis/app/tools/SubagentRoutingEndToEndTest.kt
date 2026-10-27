package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The ROUTING path, end to end.
 *
 * The other tests pin each piece in isolation. This one pins the
 * SEQUENCE the parent actually performs, because the pieces were
 * individually fine and collectively wrong: the roster was built from
 * skills only, the delegation resolution had no runtime, and the error
 * message offered names the parent could not spawn.
 *
 * It also pins the two ROUTES separately, because the difference matters:
 * a SKILL is a runtime (it can be spawned as-is), a CONFIGURED entry is a
 * speciality (it needs a runtime to run on).
 */
class SubagentRoutingEndToEndTest {

    private val skills = listOf(
        SubagentRosterHint.Entry("general-agent", "general research and audits"),
        SubagentRosterHint.Entry("read-only-researcher", "reads sources without writing"),
    )

    private val configured = listOf(
        SubagentDelegation.ConfiguredAgent(
            name = "security-reviewer",
            description = "use before merging",
            instructions = "Audit the diff for injection, SSRF and secret leakage.",
        ),
        SubagentDelegation.ConfiguredAgent(
            name = "general-agent",
            description = "use for deep multi-file audits",
            instructions = "",
        ),
    )

    /** The parent sees ONE merged roster. */
    @Test
    fun `the parent sees both routes in one roster`() {
        val roster = SubagentRosterHint.build(
            SubagentRosterHint.merge(skills, configured.map { c ->
                SubagentRosterHint.Entry(c.name, c.description)
            }),
        )
        assertTrue("the skill is listed", roster.contains("general-agent"))
        assertTrue("the configured speciality is listed", roster.contains("security-reviewer"))
        // On a name clash the skill wins as the runtime, but the CONFIGURED
        // description is the routing text — the user wrote it to say WHEN.
        assertTrue(
            "the configured routing text wins the name clash",
            roster.contains("general-agent: use for deep multi-file audits"),
        )
    }

    /** A configured speciality resolves to a spawnable runtime + mission. */
    @Test
    fun `a configured speciality resolves to a runtime and a mission`() {
        val d = SubagentDelegation.resolve(
            requested = "security-reviewer",
            skillRoster = skills,
            configured = configured,
            skillExists = { it == "general-agent" || it == "read-only-researcher" },
        )
        assertNotNull("the configured speciality must resolve", d)
        assertEquals("general-agent", d!!.skillName)
        assertTrue(
            "the mission reaches the spawn",
            d.extraInstructions.contains("injection") &&
                d.extraInstructions.contains("SSRF"),
        )
    }

    /** A skill resolves to itself, with no mission bolted on. */
    @Test
    fun `a skill resolves to itself`() {
        val d = SubagentDelegation.resolve(
            requested = "read-only-researcher",
            skillRoster = skills,
            configured = configured,
            skillExists = { it == "general-agent" || it == "read-only-researcher" },
        )
        assertNotNull(d)
        assertEquals("read-only-researcher", d!!.skillName)
        assertTrue("no mission for a plain skill spawn", d.extraInstructions.isBlank())
    }

    /** A name that matches neither route does NOT resolve. */
    @Test
    fun `an unknown name does not resolve`() {
        val d = SubagentDelegation.resolve(
            requested = "nope",
            skillRoster = skills,
            configured = configured,
            skillExists = { it == "general-agent" || it == "read-only-researcher" },
        )
        assertNull(d)
    }

    /**
     * The error message is part of the routing: the parent's standing
     * instruction is \"do not retry the same spawn\", so a bare \"not found\"
     * loses the delegation for the whole turn. The message must name the
     * alternatives — and those alternatives must be the SAME list the
     * parent sees in the schema.
     */
    @Test
    fun `the error names the same roster the parent sees`() {
        val merged = SubagentRosterHint.merge(skills, configured.map { c ->
            SubagentRosterHint.Entry(c.name, c.description)
        })
        val msg = SubagentRosterHint.unknownAgentMessage("nope", merged)
        assertTrue("names the skill", msg.contains("general-agent"))
        assertTrue("names the configured speciality", msg.contains("security-reviewer"))
    }
}
