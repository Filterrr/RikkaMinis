package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/subagent-roster-routing] Source-grep drift guard for the model
 * choice.
 *
 * [SubagentModelChoiceTest] pins the rule itself — the precedence between
 * the parent's explicit choice, the roster's pinned model group, and the
 * skill's default model. That test cannot see whether the RUNNER
 * actually asks the rule, and that call site is exactly where the
 * defect would live: the rule can be correct while the runner keeps its
 * own inline copy of it, and the two drift apart silently.
 *
 * This is the same technique the repo already uses for the allowlist
 * gate (see [SubagentAllowedToolsWiringTest]).
 *
 * It is here because the negative verification caught exactly this: the
 * pure function was extracted, tested, and then the runner was left
 * calling its old inline logic. The unit test stayed green.
 */
class SubagentModelChoiceWiringTest {

    private val runnerSource: String by lazy {
        ProductionSources.read("tools/SubagentRunner.kt")
    }

    @Test
    fun `the runner asks the shared model-choice rule`() {
        assertTrue(
            "the runner must resolve the model through SubagentModelChoice.resolve " +
                "— an inline copy of the precedence will drift",
            runnerSource.contains("SubagentModelChoice.resolve("),
        )
    }

    @Test
    fun `the runner asks the shared group-membership rule`() {
        assertTrue(
            "the runner must check the pinned-group membership through " +
                "SubagentModelChoice.isOutsidePinnedGroup",
            runnerSource.contains("SubagentModelChoice.isOutsidePinnedGroup("),
        )
    }

    /**
     * The runner must not keep its own copy of the precedence.
     *
     * The inline shape was `rosterGroupId?.let { "group:$it" } ?:
     * config.model?.trim().orEmpty()`. If that comes back while
     * `SubagentModelChoice.resolve` is also called, there are two
     * implementations and the tested one is not the one that runs.
     */
    @Test
    fun `the runner does not keep an inline copy of the precedence`() {
        // The inline shape was `rosterGroupId?.let { "group:$it" } ?:
        // config.model?.trim().orEmpty()`. The regex is deliberately
        // loose about the whitespace and the exact `?.` / `?:` spelling:
        // the point is to catch a SECOND implementation of the
        // precedence, not to pin one formatting.
        val inlinePrecedence = Regex(
            "rosterGroupId\\s*\\?\\.let\\s*\\{[^}]*group:[^}]*\\}\\s*\\??\\s*:\\s*config\\.model",
        )
        assertFalse(
            "the runner must not keep an inline copy of the model precedence — " +
                "the shared rule is the one that is tested",
            inlinePrecedence.containsMatchIn(runnerSource),
        )
    }

    /**
     * The shared rule must not be short-circuited into a no-op.
     */
    @Test
    fun `the shared rule is not bypassed`() {
        assertFalse(
            "the model choice must not be bypassed",
            runnerSource.contains("false && SubagentModelChoice.resolve"),
        )
    }
}
