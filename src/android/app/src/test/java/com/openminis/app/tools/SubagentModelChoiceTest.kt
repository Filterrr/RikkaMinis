package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] The model precedence rule.
 *
 * Three sources can name a model, and the order is a PRODUCT decision:
 *
 *   1. the parent's explicit `model` argument;
 *   2. the roster entry's pinned model GROUP;
 *   3. the skill's own `model:` default.
 *
 * The rule was inline in the runner — an Android-dependent file the local
 * JVM script cannot compile — so it could only be checked on CI. It is
 * now a pure function and the runner calls THAT, so this test covers the
 * code that actually runs.
 */
class SubagentModelChoiceTest {

    @Test
    fun `the parent's explicit model wins over everything`() {
        assertEquals(
            "the parent's explicit argument is the most specific instruction",
            "gpt-5",
            SubagentModelChoice.resolve(
                requestedModelArg = "gpt-5",
                rosterGroupId = "cheap",
                skillDefaultModel = "claude",
            ),
        )
    }

    @Test
    fun `the roster's pinned group outranks the skill's default`() {
        assertEquals(
            "the roster entry is the user's configuration for THIS agent, so it " +
                "outranks the skill author's general default",
            "group:cheap",
            SubagentModelChoice.resolve(
                requestedModelArg = "",
                rosterGroupId = "cheap",
                skillDefaultModel = "claude",
            ),
        )
    }

    @Test
    fun `the skill's default is used when nothing else names a model`() {
        assertEquals(
            "claude",
            SubagentModelChoice.resolve(
                requestedModelArg = "",
                rosterGroupId = null,
                skillDefaultModel = "claude",
            ),
        )
    }

    @Test
    fun `nothing named means nothing named`() {
        assertEquals(
            "",
            SubagentModelChoice.resolve(
                requestedModelArg = "",
                rosterGroupId = null,
                skillDefaultModel = null,
            ),
        )
    }

    @Test
    fun `blank arguments are treated as absent`() {
        assertEquals(
            "group:cheap",
            SubagentModelChoice.resolve(
                requestedModelArg = "   ",
                rosterGroupId = "cheap",
                skillDefaultModel = "  ",
            ),
        )
    }

    /**
     * The pinned group is a CONSTRAINT, not a suggestion. The user pinned
     * it precisely to keep this agent on it.
     */
    @Test
    fun `a model outside the pinned group is refused`() {
        assertTrue(
            "a model the parent names that is not in the pinned group must be " +
                "refused rather than silently honoured",
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "gpt-5",
                pinnedGroupId = "cheap",
                groupMemberIds = listOf("claude-haiku"),
                groupMemberDisplayNames = listOf("Claude Haiku"),
                groupMemberEntryIds = listOf("entry-1"),
            ),
        )
    }

    @Test
    fun `a model in the pinned group is allowed`() {
        assertFalse(
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "claude-haiku",
                pinnedGroupId = "cheap",
                groupMemberIds = listOf("claude-haiku"),
                groupMemberDisplayNames = listOf("Claude Haiku"),
                groupMemberEntryIds = listOf("entry-1"),
            ),
        )
    }

    @Test
    fun `the display name is accepted as well as the id`() {
        assertFalse(
            "the parent may name a group member by its display name",
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "Claude Haiku",
                pinnedGroupId = "cheap",
                groupMemberIds = listOf("claude-haiku"),
                groupMemberDisplayNames = listOf("Claude Haiku"),
                groupMemberEntryIds = listOf("entry-1"),
            ),
        )
    }

    @Test
    fun `the entry id is accepted as well`() {
        assertFalse(
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "entry-1",
                pinnedGroupId = "cheap",
                groupMemberIds = listOf("claude-haiku"),
                groupMemberDisplayNames = listOf("Claude Haiku"),
                groupMemberEntryIds = listOf("entry-1"),
            ),
        )
    }

    @Test
    fun `no group means no constraint`() {
        assertFalse(
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "gpt-5",
                pinnedGroupId = null,
                groupMemberIds = listOf("claude-haiku"),
            ),
        )
    }

    @Test
    fun `naming no model is never a violation`() {
        assertFalse(
            "a blank request lets the group choose — that is not a violation",
            SubagentModelChoice.isOutsidePinnedGroup(
                requestedModelArg = "  ",
                pinnedGroupId = "cheap",
                groupMemberIds = listOf("claude-haiku"),
            ),
        )
    }
}
