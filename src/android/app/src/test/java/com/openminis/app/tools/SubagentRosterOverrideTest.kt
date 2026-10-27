package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The roster entry's model group and
 * reasoning-level override.
 *
 * Both fields existed in the data model from the start and were never
 * read by the runtime — and, until this branch, not reachable from the UI
 * either. A stored field nobody reads is a promise the product does not
 * keep; these tests pin the reading.
 */
class SubagentRosterOverrideTest {

    private val skillRoster = listOf(
        SubagentRosterHint.Entry("general-agent", "general research"),
    )

    @Test
    fun `a roster entry carries its model group into the resolved target`() {
        val d = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = skillRoster,
            configured = listOf(
                SubagentDelegation.ConfiguredAgent(
                    name = "reviewer",
                    description = "use before merging",
                    instructions = "audit it",
                    modelGroupId = "cheap-tier",
                ),
            ),
            skillExists = { it == "general-agent" },
        )
        assertEquals("cheap-tier", d!!.modelGroupId)
    }

    @Test
    fun `a roster entry carries its reasoning override into the resolved target`() {
        val d = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = skillRoster,
            configured = listOf(
                SubagentDelegation.ConfiguredAgent(
                    name = "reviewer",
                    description = "use before merging",
                    thinkingLevelOverride = "HIGH",
                ),
            ),
            skillExists = { it == "general-agent" },
        )
        assertEquals("HIGH", d!!.thinkingLevelOverride)
    }

    @Test
    fun `a plain skill spawn carries no overrides`() {
        val d = SubagentDelegation.resolve(
            requested = "general-agent",
            skillRoster = skillRoster,
            configured = emptyList(),
            skillExists = { it == "general-agent" },
        )
        assertNull(d!!.modelGroupId)
        assertNull(d.thinkingLevelOverride)
    }

    @Test
    fun `a blank override is treated as absent`() {
        val d = SubagentDelegation.resolve(
            requested = "reviewer",
            skillRoster = skillRoster,
            configured = listOf(
                SubagentDelegation.ConfiguredAgent(
                    name = "reviewer",
                    description = "use before merging",
                    modelGroupId = "  ",
                    thinkingLevelOverride = "",
                ),
            ),
            skillExists = { it == "general-agent" },
        )
        assertNull(d!!.modelGroupId)
        assertNull(d.thinkingLevelOverride)
    }
}
